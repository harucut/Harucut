package com.harucut.media.compose;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

// 이 테스트가 존재하는 이유: claim 이 AFTER_COMMIT 리스너 안에서 조용히 죽어
// "즉시 접수"가 통째로 동작하지 않던 사고가 있었다. 목으로 만든 단위 테스트는 이걸 못 잡는다 —
// ComposeService 를 목으로 바꾸면 claim 이 항상 true 를 돌려주기 때문이다.
// 그래서 실제 트랜잭션 경계와 실제 리스너를 태운다.
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("합성 선점 트랜잭션 경계")
class ComposeClaimTransactionTest {

    private static final String PUBLIC_ROOT = "uploads/users/probe00000001/";
    private static final List<String> SOURCE_KEYS = List.of(
            PUBLIC_ROOT + "fourcuts/sources/1.png",
            PUBLIC_ROOT + "fourcuts/sources/2.png",
            PUBLIC_ROOT + "fourcuts/sources/3.png",
            PUBLIC_ROOT + "fourcuts/sources/4.png");

    @Autowired
    private ComposeService composeService;

    @Autowired
    private ComposeJobRepository composeJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 실제 AWS 호출만 막는다. 리스너·서비스·트랜잭션은 전부 진짜다
    @MockitoBean
    private ComposeExecutor composeExecutor;

    private ComposeSpec spec() {
        return new ComposeSpec(
                FrameType.CLASSIC.getLayout().canvasWidth(),
                FrameType.CLASSIC.getLayout().canvasHeight(),
                new BackgroundAttributes.Color("#112233"),
                FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false),
                List.of());
    }

    private Long saveJob(String email) {
        User user = userRepository.save(UserFixtures.localUser(email, "pw"));
        return composeJobRepository.save(
                ComposeJob.create(user, 1L, "idem-" + email, SOURCE_KEYS, spec())).getId();
    }

    private ComposeRequestedEvent eventFor(Long jobId) {
        return new ComposeRequestedEvent(jobId, spec(), SOURCE_KEYS,
                PUBLIC_ROOT + "fourcuts/job-" + jobId + ".png",
                PUBLIC_ROOT + "fourcuts/job-" + jobId + "-thumb.jpg",
                ImageFormat.PNG);
    }

    @Test
    @DisplayName("AFTER_COMMIT 리스너가 Job 을 선점하고 실행기까지 부른다")
    void claimsAndDispatchesFromAfterCommitListener() {
        Long jobId = transactionTemplate.execute(status -> {
            Long id = saveJob("claim-listener@harucut.com");
            eventPublisher.publishEvent(eventFor(id));
            return id;
        });

        // startedAt 이 찍혔다 = 벌크 UPDATE 가 실제 트랜잭션 안에서 돌았다
        assertThat(composeJobRepository.findById(jobId).orElseThrow().getStartedAt())
                .as("AFTER_COMMIT 에서 claim 이 죽으면 null 로 남는다")
                .isNotNull();

        // 접수가 실제로 나갔다 = claim 이 false 를 돌려주고 조기 반환한 게 아니다
        then(composeExecutor).should().execute(any(ComposeRequestedEvent.class));
    }

    @Test
    @DisplayName("트랜잭션 밖(재실행 스케줄러 경로)에서도 선점된다")
    void claimsOutsideTransaction() {
        Long jobId = transactionTemplate.execute(status -> saveJob("claim-outside@harucut.com"));

        assertThat(composeService.claim(jobId, Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(composeJobRepository.findById(jobId).orElseThrow().getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 선점된 Job 은 유예 시간 안에서는 두 번 선점되지 않는다")
    void secondClaimIsRejectedWithinStaleWindow() {
        Long jobId = transactionTemplate.execute(status -> saveJob("claim-twice@harucut.com"));

        assertThat(composeService.claim(jobId, Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(composeService.claim(jobId, Duration.ofMinutes(10)))
                .as("유예 시간 안에서는 뒤늦은 재실행이 같은 Job 을 다시 집으면 안 된다")
                .isNull();
        // 유예가 지나 다시 집으면 시도 번호가 오른다
        assertThat(composeService.claim(jobId, Duration.ZERO)).isEqualTo(2);
    }
}
