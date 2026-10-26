package com.harucut.participant.race;

import com.harucut.booth.entity.BoothDevice;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.common.exception.BusinessException;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.entity.ParticipantSession;
import com.harucut.participant.enums.ConsentDecision;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.participant.service.ParticipantService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.support.TestEntitlements;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 엔티티를 읽은 뒤 다른 트랜잭션이 같은 행을 바꿔 커밋했을 때(JDBC 직접 UPDATE 로 흉내), 엔티티 저장이 그 변경을 되돌리지 않는지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("엔티티 저장과 동시 회수의 경합")
class ConcurrentEntityUpdateTest {

    @Autowired private BoothDeviceRepository boothDeviceRepository;
    @Autowired private ParticipantService participantService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ParticipantSessionRepository sessionRepository;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private WorkspaceRepository workspaceRepository;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("기기 확인(last_seen_at 저장)이 그사이 커밋된 회수를 되돌리지 않는다")
    void boothSeenKeepsRevocation() {
        Event event = publishedEvent("race-booth");
        BoothDevice device = boothDeviceRepository.save(BoothDevice.pending(event.getWorkspaceId(), event.getId(), "부스"));
        device.pair("a".repeat(64));
        entityManager.flush();

        BoothDevice loaded = boothDeviceRepository.findById(device.getId()).orElseThrow();
        jdbcTemplate.update("update booth_device set status = 'REVOKED', token_hash = null where booth_device_id = ?", device.getId());
        loaded.seen(LocalDateTime.now());
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("select status from booth_device where booth_device_id = ?", String.class, device.getId()))
                .isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("select token_hash from booth_device where booth_device_id = ?", String.class, device.getId()))
                .isNull();
    }

    @Test
    @DisplayName("동의하는 사이 세션이 회수됐으면 ACTIVE 로 되살리지 않고 PART-001")
    void consentDoesNotReviveRevokedSession() {
        Event event = publishedEvent("race-consent");
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ParticipantSession session = sessionRepository.save(
                ParticipantSession.restricted(participant.getId(), "b".repeat(64), LocalDateTime.now().plusHours(1)));
        entityManager.flush();
        ParticipantPrincipal principal = new ParticipantPrincipal(session.getId(), participant.getId(), participant.getPublicId(),
                event.getId(), ParticipantChannel.BYOD, null);
        jdbcTemplate.update("update participant_session set status = 'REVOKED' where participant_session_id = ?", session.getId());

        assertThatThrownBy(() -> participantService.consent(principal, event.getPublicId(), new ParticipantDtos.ConsentRequest(
                List.of(new ParticipantDtos.ConsentRequest.ConsentItem("c", 1, ConsentDecision.AGREED)))))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ParticipantErrorCode.SESSION_INVALID);
        assertThat(jdbcTemplate.queryForObject("select status from participant_session where participant_session_id = ?",
                String.class, session.getId())).isEqualTo("REVOKED");
    }

    private Event publishedEvent(String slug) {
        User owner = userRepository.save(User.localUser(slug + "@harucut.com", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "공간");
        // 게시에는 적용 이용권이 필요하다(ADR-0008 §6-2)
        TestEntitlements.grant(entitlementRepository, workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId());
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        Long frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
        eventService.publish(owner.getPublicId(), created.eventId());
        return eventRepository.findByPublicId(created.eventId()).orElseThrow();
    }
}
