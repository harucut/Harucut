package com.harucut.entitlement;

import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.RateLimiter;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EntitlementStatus;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.enums.InquiryStatus;
import com.harucut.entitlement.enums.LedgerKind;
import com.harucut.entitlement.enums.OrganizerType;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.entitlement.repository.UsageLedgerRepository;
import com.harucut.entitlement.service.ContractService;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.entitlement.service.InquiryService;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.service.ExportService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * P2B-5 — 계약·이용권·사용량 원장을 실제 JPA 로 본다(ADR-0008 §6). 확정·반환은 실제 completeJob·failJob 전이를 탄다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("계약·이용권·사용량 — 통합")
class EntitlementIntegrationTest {

    @Autowired private EntitlementService entitlementService;
    @Autowired private ContractService contractService;
    @Autowired private InquiryService inquiryService;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private UsageLedgerRepository ledgerRepository;
    @Autowired private EventService eventService;
    @Autowired private ComposeService composeService;
    @Autowired private ExportService exportService;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private com.harucut.audit.repository.AuditLogRepository auditLogRepository;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User admin;
    private User owner;
    private WorkspaceResponse org;
    private Long workspaceId;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        admin = userRepository.save(User.localUser("admin@harucut.com", "e", "관리자"));
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameId).frameVersionId();
    }

    @Test
    @DisplayName("이용권 없이 게시하면 403 ENT-001")
    void publishNeedsEntitlement() {
        EventResponse event = ready("no-ent");
        assertCode(() -> eventService.publish(owner.getPublicId(), event.eventId()), EntitlementErrorCode.NO_ENTITLEMENT);
    }

    @Test
    @DisplayName("원장: 한도까지만 예약, 성공은 확정·실패는 반환(한 번만), 늦은 통지는 종결된 예약을 되살리지 않는다")
    void reserveCommitRelease() {
        grant(2, EnumSet.noneOf(Feature.class));
        Event event = published("ledger");
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob first = job(event, participant, "k1");
        ComposeJob second = job(event, participant, "k2");
        ComposeJob third = job(event, participant, "k3");

        entitlementService.reserve(first.getId(), event);
        entitlementService.reserve(second.getId(), event);
        assertCode(() -> entitlementService.reserve(third.getId(), event), EntitlementErrorCode.QUOTA_REACHED);
        assertCode(() -> entitlementService.requireRemaining(event), EntitlementErrorCode.QUOTA_REACHED);
        assertThat(usage(event)).containsExactly(2, 0, 2, 0);

        assertThat(composeService.completeJob(first.getId(), "uploads/r/1.jpg", "uploads/r/1_thumb.jpg")).isTrue();
        assertThat(composeService.failJob(second.getId(), "boom")).isTrue();
        assertThat(usage(event)).containsExactly(2, 1, 0, 1);

        // 늦게 온 통지 — 전이에서 지고, 직접 불러도 종결된 예약은 바뀌지 않는다
        assertThat(composeService.completeJob(second.getId(), "uploads/r/2.jpg", "uploads/r/2_thumb.jpg")).isFalse();
        entitlementService.commit(second.getId());
        entitlementService.release(first.getId());
        assertThat(usage(event)).containsExactly(2, 1, 0, 1);

        entitlementService.reserve(third.getId(), event);   // 반환된 자리를 쓴다
        assertThat(usage(event)).containsExactly(2, 1, 1, 0);
        assertThat(ledgerRepository.findAllByComposeJobIdOrderByIdAsc(first.getId())).extracting(l -> l.getKind())
                .containsExactly(LedgerKind.RESERVE, LedgerKind.COMMIT);
        assertThat(ledgerRepository.findAllByComposeJobIdOrderByIdAsc(second.getId())).extracting(l -> l.getKind())
                .containsExactly(LedgerKind.RESERVE, LedgerKind.RELEASE);
    }

    @Test
    @DisplayName("계약: DRAFT 는 적용 안 됨 → ACTIVE 면 이용권 → ENDED 면 끝, 허용 안 되는 전이는 ENT-003")
    void contractLifecycle() {
        Event event = eventRepository.findByPublicId(ready("contract").eventId()).orElseThrow();
        EntitlementDtos.Contract contract = contractService.create(admin.getPublicId(), new EntitlementDtos.ContractRequest(
                org.workspaceId(), "웨딩홀 주식회사", EntitlementScope.WORKSPACE, null, 1_000_000L, "KRW",
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusMonths(1),
                new EntitlementDtos.Evidence(EvidenceKind.BANK_TRANSFER, "입금 2026-10-26"),
                new EntitlementDtos.Terms(500, 30, 3, 2, Set.of(Feature.BULK_DOWNLOAD))));
        assertThat(contract.status()).isEqualTo(ContractStatus.DRAFT);
        assertThat(entitlementService.applicable(event)).isEmpty();

        contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ACTIVE);
        Entitlement applied = entitlementService.applicable(event).orElseThrow();
        assertThat(applied.getComposeQuota()).isEqualTo(500);
        assertThat(applied.allows(Feature.BULK_DOWNLOAD)).isTrue();

        contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ENDED);
        entityManager.flush();
        assertThat(entitlementService.applicable(event)).isEmpty();
        assertCode(() -> contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ACTIVE),
                EntitlementErrorCode.INVALID_TRANSITION);
    }

    @Test
    @DisplayName("행사 범위가 공간 범위보다 먼저, 다른 공간 행사로는 계약할 수 없다")
    void eventScopeWins() {
        grant(100, EnumSet.noneOf(Feature.class));
        Event event = eventRepository.findByPublicId(ready("scoped").eventId()).orElseThrow();
        EntitlementDtos.Entitlement eventGrant = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(
                org.workspaceId(), event.getPublicId(), OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                new EntitlementDtos.Terms(7, null, null, null, Set.of()), "파트너 체험"));
        assertThat(entitlementService.applicable(event).orElseThrow().getPublicId()).isEqualTo(eventGrant.entitlementId());

        User other = userRepository.save(User.localUser("o@harucut.com", "e", "o"));
        WorkspaceResponse otherOrg = workspaceService.createOrganization(other.getPublicId(), "다른 곳");
        assertCode(() -> contractService.create(admin.getPublicId(), new EntitlementDtos.ContractRequest(otherOrg.workspaceId(),
                "다른 곳", EntitlementScope.EVENT, List.of(event.getPublicId()), null, null, OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(1), null, new EntitlementDtos.Terms(1, null, null, null, null))), GlobalErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("수동 부여 → 영향 미리보기(운영 중 행사·예약) → 회수하면 적용 끝, 다시 회수는 ENT-003")
    void manualGrantImpactRevoke() {
        EntitlementDtos.Entitlement granted = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(
                org.workspaceId(), null, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                new EntitlementDtos.Terms(10, null, null, null, Set.of()), "체험"));
        Event event = published("impact");
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        entitlementService.reserve(job(event, participant, "k").getId(), event);

        EntitlementDtos.Impact impact = contractService.impact(granted.entitlementId());
        assertThat(impact.liveEvents()).containsExactly(event.getPublicId());
        assertThat(impact.openReservations()).isEqualTo(1);

        contractService.revoke(admin.getPublicId(), granted.entitlementId(), "체험 종료");
        assertThat(entitlementService.applicable(event)).isEmpty();
        assertCode(() -> contractService.revoke(admin.getPublicId(), granted.entitlementId(), "또"), EntitlementErrorCode.INVALID_TRANSITION);
    }

    @Test
    @DisplayName("계약 조건: 묶음은 BULK_DOWNLOAD 필요(ENT-004), 부스 수·동시 운영 행사 수·보관 일수 상한(PATCH 에서)")
    void termsEnforced() {
        LocalDateTime now = LocalDateTime.now();
        entitlementRepository.save(Entitlement.manual(workspaceId, null, new Contract.Terms(100, 30, 1, 1, Set.of()),
                now.minusDays(1), now.plusDays(10), null, "작은 계약"));
        Event first = published("terms-1");

        assertCode(() -> exportService.create(owner.getPublicId(), first.getPublicId(), new ExportDtos.ExportRequest(null, null)),
                EntitlementErrorCode.FEATURE_NOT_ENTITLED);
        boothDeviceService.create(owner.getPublicId(), first.getPublicId(), "1번");
        assertCode(() -> boothDeviceService.create(owner.getPublicId(), first.getPublicId(), "2번"), BoothErrorCode.DEVICE_LIMIT);

        EventResponse second = ready("terms-2");
        // 보관 일수 상한은 PATCH 에서 막는다(게시 뒤에도 넘기지 못하게)
        assertCode(() -> eventService.patch(owner.getPublicId(), second.eventId(), new EventPatchRequest(
                eventService.get(owner.getPublicId(), second.eventId()).settingsVersion(),
                objectMapper.valueToTree(Map.of("resultRetentionDays", 60)))), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() -> eventService.publish(owner.getPublicId(), second.eventId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> violations = (List<Map<String, String>>) ((Map<?, ?>) e.getData()).get("violations");
                    assertThat(violations).extracting(v -> v.get("field")).containsExactly("status");
                });
    }

    @Test
    @DisplayName("리뷰 반영: 게시 뒤 PATCH 로 보관 일수 상한을 넘길 수 없다, 촬영 종료 행사 재게시는 운영 중 수에 안 걸린다")
    void retentionCapOnPatchAndRepublish() {
        LocalDateTime now = LocalDateTime.now();
        entitlementRepository.save(Entitlement.manual(workspaceId, null, new Contract.Terms(100, 30, null, 1, Set.of()),
                now.minusDays(1), now.plusDays(10), null, "t"));
        Event first = published("cap-1");

        assertCode(() -> eventService.patch(owner.getPublicId(), first.getPublicId(), new EventPatchRequest(
                eventService.get(owner.getPublicId(), first.getPublicId()).settingsVersion(),
                objectMapper.valueToTree(Map.of("resultRetentionDays", 365)))), GlobalErrorCode.INVALID_INPUT_VALUE);

        // 운영 중 1개 상한 — 촬영 종료 뒤 다시 게시해도 LIVE 가 늘지 않으니 위반이 아니다
        eventService.closeCapture(owner.getPublicId(), first.getPublicId());
        EventResponse other = ready("cap-2");
        eventService.publish(owner.getPublicId(), other.eventId());   // 이제 LIVE 1개
        eventService.publish(owner.getPublicId(), first.getPublicId());
    }

    @Test
    @DisplayName("리뷰 반영: 같은 PORTONE 결제를 두 계약에 걸 수 없다, 상태 전이 응답은 바뀐 상태를 보여 준다")
    void contractEvidenceUniqueAndTransitionView() {
        EntitlementDtos.ContractRequest request = new EntitlementDtos.ContractRequest(org.workspaceId(), "웨딩홀",
                EntitlementScope.WORKSPACE, null, 1000L, "KRW", OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                new EntitlementDtos.Evidence(EvidenceKind.PORTONE, "pay-dup"), new EntitlementDtos.Terms(10, null, null, null, Set.of()));
        EntitlementDtos.Contract contract = contractService.create(admin.getPublicId(), request);
        assertCode(() -> contractService.create(admin.getPublicId(), request), GlobalErrorCode.INVALID_INPUT_VALUE);

        assertThat(contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ACTIVE).status())
                .isEqualTo(ContractStatus.ACTIVE);
        assertCode(() -> contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ACTIVE),
                EntitlementErrorCode.INVALID_TRANSITION);
        assertThat(entitlementRepository.findAll()).filteredOn(e -> e.getContractId() != null).hasSize(1);   // 이용권은 한 번만
    }

    @Test
    @DisplayName("문의: 로그인 없이 접수, IP 빈도 초과는 GEN-061, 관리자가 상태·메모를 바꾼다")
    void inquiries() {
        EntitlementDtos.InquiryCreated created = inquiryService.create(new EntitlementDtos.InquiryRequest("김신부", "bride@example.com",
                null, OrganizerType.PERSONAL, Set.of(ParticipantChannel.BOOTH, ParticipantChannel.BYOD), null, 150, "결혼식 부스 문의"), "1.2.3.4");
        assertThat(inquiryService.list()).singleElement().satisfies(i -> {
            assertThat(i.channels()).isEqualTo("BOOTH,BYOD");
            assertThat(i.status()).isEqualTo(InquiryStatus.NEW);
        });
        assertThat(inquiryService.update(created.inquiryId(), new EntitlementDtos.InquiryUpdateRequest(InquiryStatus.QUOTED, "견적 발송"))
                .assigneeNote()).isEqualTo("견적 발송");

        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(false);
        assertCode(() -> inquiryService.create(new EntitlementDtos.InquiryRequest("x", "x@example.com", null, OrganizerType.BUSINESS,
                Set.of(ParticipantChannel.BYOD), null, null, "x"), "1.2.3.4"), GlobalErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("계약이 끝나면(validTo 단축, status 는 ACTIVE) 예약 UPDATE 가 0행 — 이미 잡힌 예약의 확정은 그대로")
    void reserveChecksValidity() {
        EntitlementDtos.Contract contract = contractService.create(admin.getPublicId(), new EntitlementDtos.ContractRequest(
                org.workspaceId(), "웨딩홀", EntitlementScope.WORKSPACE, null, 1000L, "KRW",
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusMonths(1), null,
                new EntitlementDtos.Terms(10, null, null, null, Set.of())));
        contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ACTIVE);
        Event event = published("validity");
        Long entitlementId = entitlementService.applicable(event).orElseThrow().getId();
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob reserved = job(event, participant, "before-end");
        entitlementService.reserve(reserved.getId(), event);

        contractService.changeStatus(admin.getPublicId(), contract.contractId(), ContractStatus.ENDED);
        entityManager.flush();
        entityManager.clear();
        // 종료 전에 읽은 후보로 예약을 시도해도(스냅샷·경합) 원자적 UPDATE 가 유효기간에서 막는다
        assertThat(entitlementRepository.reserve(entitlementId, LocalDateTime.now())).isZero();

        assertThat(composeService.completeJob(reserved.getId(), "uploads/r/1.jpg", "uploads/r/1_thumb.jpg")).isTrue();
        entityManager.flush();
        entityManager.clear();
        Entitlement ended = entitlementRepository.findById(entitlementId).orElseThrow();
        assertThat(ended.getUsed()).isEqualTo(1);
        assertThat(ended.getReserved()).isZero();
    }

    @Test
    @DisplayName("회수 영향: 행사 범위 이용권에 덮인 행사·이미 회수된 이용권은 운영 중 행사로 세지 않는다")
    void impactCountsOnlyEventsUsingThisEntitlement() {
        EntitlementDtos.Entitlement workspaceGrant = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(
                org.workspaceId(), null, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                new EntitlementDtos.Terms(10, null, null, null, Set.of()), "공간"));
        Event covered = published("covered");
        Event plain = published("plain");
        EntitlementDtos.Entitlement eventGrant = contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(
                org.workspaceId(), covered.getPublicId(), OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10),
                new EntitlementDtos.Terms(5, null, null, null, Set.of()), "행사"));

        assertThat(contractService.impact(workspaceGrant.entitlementId()).liveEvents()).containsExactly(plain.getPublicId());
        assertThat(contractService.impact(eventGrant.entitlementId()).liveEvents()).containsExactly(covered.getPublicId());

        contractService.revoke(admin.getPublicId(), eventGrant.entitlementId(), "끝");
        assertThat(contractService.impact(eventGrant.entitlementId()).liveEvents()).isEmpty();
        assertThat(contractService.impact(workspaceGrant.entitlementId()).liveEvents())
                .containsExactlyInAnyOrder(plain.getPublicId(), covered.getPublicId());
    }

    @Test
    @DisplayName("한도 알림: 확정 사용량이 80·95·100% 를 처음 넘을 때 한 번씩 — 사용량 alerts[] 오름차순, 감사 로그 ENTITLEMENT_USAGE_ALERT")
    void usageAlerts() {
        grant(10, EnumSet.noneOf(Feature.class));
        Event event = published("alerts");
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        for (int i = 1; i <= 10; i++) {
            ComposeJob job = job(event, participant, "alert-" + i);
            entitlementService.reserve(job.getId(), event);
            assertThat(composeService.completeJob(job.getId(), "uploads/r/" + i + ".jpg", "uploads/r/" + i + "_thumb.jpg")).isTrue();
            entityManager.flush();
            entityManager.clear();
            List<Integer> thresholds = entitlementService.usage(event).alerts().stream()
                    .map(EntitlementDtos.EventUsage.Alert::threshold).toList();
            if (i < 8) {
                assertThat(thresholds).as("used=" + i).isEmpty();
            } else if (i < 10) {
                assertThat(thresholds).as("used=" + i).containsExactly(80);   // 9 = 90% 는 95 전
            } else {
                assertThat(thresholds).containsExactly(80, 95, 100);
            }
        }
        assertThat(entitlementService.usage(event).alerts()).allSatisfy(a -> assertThat(a.reachedAt()).isNotNull());
        assertThat(auditLogRepository.findAll()).filteredOn(log -> "ENTITLEMENT_USAGE_ALERT".equals(log.getAction())).hasSize(3);

        // 이용권이 없으면 빈 목록
        Event other = eventRepository.findByPublicId(ready("no-alerts").eventId()).orElseThrow();
        entitlementRepository.findAll().forEach(e -> e.endAt(java.time.LocalDateTime.now().minusMinutes(1)));
        entityManager.flush();
        assertThat(entitlementService.usage(other).alerts()).isEmpty();
    }

    @Test
    @DisplayName("행사 배분: 합이 총량을 넘으면 ENT-005(data), 사용·예약 아래로 줄이면 ENT-006(data), 같은 행사 두 번은 GEN-002 — 목록에 부모 id·배분 합")
    void allocations() {
        String parentId = manualParent(10);
        Event a = published("alloc-a");
        Event b = published("alloc-b");

        EntitlementDtos.Entitlement childA = contractService.allocate(admin.getPublicId(), parentId,
                new EntitlementDtos.AllocationRequest(a.getPublicId(), 6));
        assertThat(childA.parentEntitlementId()).isEqualTo(parentId);
        assertThat(childA.scope()).isEqualTo(EntitlementScope.EVENT);
        assertThatThrownBy(() -> contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(b.getPublicId(), 5)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(EntitlementErrorCode.ALLOCATION_EXCEEDS_PARENT);
                    assertThat(e.getData()).isEqualTo(Map.of("parentQuota", 10, "allocated", 6, "requested", 5));
                });
        contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(b.getPublicId(), 4));
        assertCode(() -> contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(a.getPublicId(), 1)),
                GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> contractService.resize(admin.getPublicId(), childA.entitlementId(), new EntitlementDtos.AllocationResizeRequest(7)),
                EntitlementErrorCode.ALLOCATION_EXCEEDS_PARENT);

        // 배분 A 에서 3건 예약 → 2 로 줄이면 ENT-006, 3 으로는 된다(남는 3 은 부모로 돌아간다)
        Participant participant = participantRepository.save(Participant.byod(a.getId()));
        for (int i = 0; i < 3; i++) {
            entitlementService.reserve(job(a, participant, "res-" + i).getId(), a);
        }
        assertThatThrownBy(() -> contractService.resize(admin.getPublicId(), childA.entitlementId(), new EntitlementDtos.AllocationResizeRequest(2)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(EntitlementErrorCode.ALLOCATION_BELOW_USAGE);
                    assertThat(e.getData()).isEqualTo(Map.of("used", 0, "reserved", 3, "requested", 2));
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(contractService.resize(admin.getPublicId(), childA.entitlementId(), new EntitlementDtos.AllocationResizeRequest(3))
                .terms().composeQuota()).isEqualTo(3);
        assertThat(contractService.entitlements()).filteredOn(e -> e.entitlementId().equals(parentId)).singleElement()
                .satisfies(parent -> assertThat(parent.allocatedQuota()).isEqualTo(7));
    }

    @Test
    @DisplayName("두 단 예약: 부모 → 자식 둘 다 차감, 부모가 먼저 막히면 자식이 남아도 ENT-002 — 확정은 둘 다, 사용량 parent 는 OWNER·ADMIN 에게만")
    void twoLevelReservation() {
        String parentId = manualParent(3);
        Event event = published("two-level");
        String childId = contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(event.getPublicId(), 3))
                .entitlementId();
        Event other = published("parent-only");   // 배분 없는 행사 — 부모만 쓴다
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        Participant otherParticipant = participantRepository.save(Participant.byod(other.getId()));

        ComposeJob first = job(event, participant, "two-1");
        entitlementService.reserve(first.getId(), event);
        entitlementService.reserve(job(other, otherParticipant, "parent-1").getId(), other);
        entitlementService.reserve(job(other, otherParticipant, "parent-2").getId(), other);
        // 부모 3 이 다 찼다 — 자식은 2 가 남았어도 막힌다(트랜잭션째 롤백이라 자식만 차감되는 일이 없다)
        assertCode(() -> entitlementService.reserve(job(event, participant, "two-2").getId(), event), EntitlementErrorCode.QUOTA_REACHED);
        assertCode(() -> entitlementService.requireRemaining(event), EntitlementErrorCode.QUOTA_REACHED);

        assertThat(composeService.completeJob(first.getId(), "uploads/r/two.jpg", "uploads/r/two_thumb.jpg")).isTrue();
        entityManager.flush();
        entityManager.clear();
        assertThat(ledgerRepository.findAllByComposeJobIdOrderByIdAsc(first.getId())).extracting(l -> l.getKind())
                .containsExactly(LedgerKind.RESERVE, LedgerKind.RESERVE, LedgerKind.COMMIT, LedgerKind.COMMIT);
        EntitlementDtos.EventUsage asAdmin = entitlementService.usage(event, true);
        assertThat(List.of(asAdmin.composeQuota(), asAdmin.used(), asAdmin.reserved(), asAdmin.remaining())).containsExactly(3, 1, 0, 2);
        assertThat(asAdmin.parent()).isEqualTo(new EntitlementDtos.EventUsage.Parent(3, 1, 2, 0));
        assertThat(entitlementService.usage(event, false).parent()).isNull();
        assertThat(contractService.entitlements()).filteredOn(e -> e.entitlementId().equals(childId)).singleElement()
                .satisfies(child -> assertThat(child.used()).isEqualTo(1));
    }

    @Test
    @DisplayName("회수: 배분을 회수하면 그 양이 부모로 돌아가고, 부모를 회수하면 배분도 함께 끝난다")
    void revokeCascades() {
        String parentId = manualParent(10);
        Event a = published("rev-a");
        Event b = published("rev-b");
        String childA = contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(a.getPublicId(), 6)).entitlementId();
        contractService.revoke(admin.getPublicId(), childA, "행사 취소");
        entityManager.flush();
        entityManager.clear();
        // 6 이 돌아와 b 에 10 을 줄 수 있다
        String childB = contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(b.getPublicId(), 10)).entitlementId();
        assertThat(entitlementService.applicable(a).orElseThrow().getPublicId()).isEqualTo(parentId);   // 배분이 끝나면 부모로

        contractService.revoke(admin.getPublicId(), parentId, "계약 해지");
        entityManager.flush();
        entityManager.clear();
        assertThat(contractService.entitlements()).filteredOn(e -> e.entitlementId().equals(childB)).singleElement()
                .satisfies(child -> assertThat(child.status()).isEqualTo(EntitlementStatus.REVOKED));
        assertThat(entitlementService.applicable(b)).isEmpty();
    }

    @Test
    @DisplayName("리뷰 반영(#39): 같은 배분을 두 번 회수하면 두 번째는 ENT-003·부모 합은 한 번만 줄어든다, 영향에 배분으로 운영 중인 행사가 들어간다, 500자 사유로 부모를 회수해도 롤백되지 않는다")
    void allocationReviewFixes() {
        String parentId = manualParent(10);
        Event viaChild = published("impact-child");
        Event viaParent = published("impact-parent");
        Event covered = published("impact-covered");
        String child = contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(viaChild.getPublicId(), 4))
                .entitlementId();
        // 무관한 행사 범위 이용권에 덮인 행사는 부모 회수의 영향이 아니다
        contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(org.workspaceId(), covered.getPublicId(),
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(10), new EntitlementDtos.Terms(5, null, null, null, Set.of()), "따로"));
        assertThat(contractService.impact(parentId).liveEvents())
                .containsExactlyInAnyOrder(viaChild.getPublicId(), viaParent.getPublicId());

        contractService.revoke(admin.getPublicId(), child, "취소");
        assertCode(() -> contractService.revoke(admin.getPublicId(), child, "또"), EntitlementErrorCode.INVALID_TRANSITION);
        entityManager.flush();
        entityManager.clear();
        assertThat(contractService.entitlements()).filteredOn(e -> e.entitlementId().equals(parentId)).singleElement()
                .satisfies(parent -> assertThat(parent.allocatedQuota()).isZero());

        String again = contractService.allocate(admin.getPublicId(), parentId, new EntitlementDtos.AllocationRequest(viaChild.getPublicId(), 3))
                .entitlementId();
        String longReason = "가".repeat(500);
        contractService.revoke(admin.getPublicId(), parentId, longReason);
        entityManager.flush();
        entityManager.clear();
        assertThat(contractService.entitlements()).filteredOn(e -> e.entitlementId().equals(again)).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(EntitlementStatus.REVOKED);
            assertThat(e.revokeReason()).hasSize(500).startsWith("부모 이용권 회수: ");
        });
    }

    private String manualParent(int quota) {
        return contractService.grantManually(admin.getPublicId(), new EntitlementDtos.ManualGrantRequest(org.workspaceId(), null,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30),
                new EntitlementDtos.Terms(quota, null, null, null, Set.of()), "기업 총량")).entitlementId();
    }

    // ── helpers ──────────────────────────────

    private void grant(int quota, Set<Feature> features) {
        TestEntitlements.grant(entitlementRepository, workspaceId, quota, features);
    }

    private EventResponse ready(String slug) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null, null, null));
        return eventService.patch(owner.getPublicId(), created.eventId(), new EventPatchRequest(created.settingsVersion(),
                objectMapper.valueToTree(Map.of(
                        "allowedLayouts", List.of("CLASSIC"),
                        "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                        "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))))));
    }

    private Event published(String slug) {
        EventResponse event = ready(slug);
        eventService.publish(owner.getPublicId(), event.eventId());
        return eventRepository.findByPublicId(event.eventId()).orElseThrow();
    }

    private ComposeJob job(Event event, Participant participant, String key) {
        return composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, frameVersionId));
    }

    // [quota, used, reserved, remaining]
    private List<Integer> usage(Event event) {
        entityManager.flush();
        entityManager.clear();
        EntitlementDtos.EventUsage usage = entitlementService.usage(event);
        return List.of(usage.composeQuota(), usage.used(), usage.reserved(), usage.remaining());
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
