package com.harucut.support.mysql;

import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 실제 MySQL 에서, 테스트 트랜잭션 없이(서비스의 트랜잭션 경계 그대로) 돌린다 —
 * readOnly 트랜잭션에서 쓰기를 하면 MySQL 이 거부하는데, 바깥 테스트 트랜잭션이 있으면 그 차이가 가려진다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("행사 운영 쓰기 경로 — 실제 MySQL")
class EventOperationsMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private EventRepository eventRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FrameService frameService;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("행사 복제가 쓰기 트랜잭션에서 INSERT 된다")
    void clonesEvent() {
        User owner = userRepository.save(User.localUser("owner@clone.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        EventResponse source = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("원본", "clone-source", null, null, null, null, null, null, null, null, null, null));

        EventResponse copy = eventService.cloneEvent(owner.getPublicId(), source.eventId(), "복제본", "clone-copy");

        assertThat(copy.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(eventRepository.findByPublicId(copy.eventId())).isPresent();
        assertThat(eventService.list(owner.getPublicId(), org.workspaceId())).hasSize(2);
    }

    @Test
    @DisplayName("삭제 표시의 원자적 UPDATE 와 잠금 읽기(FOR UPDATE)가 MySQL 에서 돈다")
    void atomicDeleteAndLockingReads() {
        User owner = userRepository.save(User.localUser("owner@lock.test", "e", "오너"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "공간");
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "lock-event", null, null, null, null, null, null, null, null, null, null));
        Long eventId = eventRepository.findByPublicId(event.eventId()).orElseThrow().getId();
        Participant participant = participantRepository.save(Participant.byod(eventId));
        Long frameId = frameService.createFrame(owner.getPublicId(), new FrameCreateRequest("프레임", null,
                S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId();
        ComposeJob job = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", frameId, "k",
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                eventId, null, null, null));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Integer first = transaction.execute(s -> composeJobRepository.markDeleted(job.getId(), LocalDateTime.now()));
        Integer second = transaction.execute(s -> composeJobRepository.markDeleted(job.getId(), LocalDateTime.now()));
        List<Object> deletedAt = transaction.execute(s -> composeJobRepository.lockDeletedAt(job.getId()));
        List<String> resultKey = transaction.execute(s -> composeJobRepository.lockResultKey(job.getId()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(deletedAt).hasSize(1).doesNotContainNull();
        assertThat(resultKey).containsExactly((String) null);
    }
}
