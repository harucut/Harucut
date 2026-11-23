package com.harucut.auth.batch;

import com.harucut.auth.service.UserExitService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeletionScheduler")
class UserDeletionSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 0, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserExitService userExitService;

    private UserDeletionScheduler scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        scheduler = new UserDeletionScheduler(userRepository, userExitService, clock);
    }

    @Test
    @DisplayName("오늘 자정에서 유예를 뺀 시각보다 먼저 요청한 계정을 골라 하나씩 지운다")
    void deletesExpiredRequestsOfToday() {
        LocalDateTime threshold = LocalDate.of(2026, 8, 18).atStartOfDay().minusDays(User.DELETION_GRACE_DAYS);
        given(userRepository.findExpiredExitTargetIds(threshold)).willReturn(List.of(1L, 2L));

        scheduler.run();

        then(userExitService).should().exit(1L);
        then(userExitService).should().exit(2L);
    }

    @Test
    @DisplayName("한 건이 터져도 나머지는 지우고, 지운 수만 센다")
    void oneFailureDoesNotStopTheRest() {
        // 터지는 건을 맨 앞에 둔다 — 엄격한 스텁은 아직 안 쓰인 스텁을 다른 인자로 부르면 그 호출도 예외로 본다
        given(userRepository.findExpiredExitTargetIds(any())).willReturn(List.of(2L, 1L, 3L));
        willThrow(new IllegalStateException("핸들러 고장")).given(userExitService).exit(2L);

        assertThat(scheduler.run(LocalDate.of(2026, 8, 18))).isEqualTo(2);

        then(userExitService).should().exit(1L);
        then(userExitService).should().exit(3L);
    }
}
