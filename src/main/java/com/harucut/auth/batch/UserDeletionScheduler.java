package com.harucut.auth.batch;

import com.harucut.auth.service.UserExitService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 탈퇴 하드 삭제 — 유예(User.DELETION_GRACE_DAYS)가 지난 계정을 하나씩 지운다. 건마다 UserExitService.exit 가 제 트랜잭션이라
 * 한 건이 터져도 그 건만 롤백되고 나머지는 계속된다. 실패한 건은 다음 날 다시 본다.
 * 중복 기동은 막지 않는다 — 앱이 한 대라 하루 한 번 돈다(docs/decisions.md 2026-11-03). 두 대가 되면 잠금이 필요하다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDeletionScheduler {

    private final UserRepository userRepository;
    private final UserExitService userExitService;
    private final Clock clock;

    @Scheduled(cron = "0 0 1 * * *")
    public void run() {
        run(LocalDate.now(clock));
    }

    /** runDate 자정 기준으로 유예가 지난 계정을 지운다. @return 지운 수 */
    public int run(LocalDate runDate) {
        LocalDateTime threshold = runDate.atStartOfDay().minusDays(User.DELETION_GRACE_DAYS);
        int deleted = 0;
        for (Long userId : userRepository.findExpiredExitTargetIds(threshold)) {
            try {
                userExitService.exit(userId);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("[탈퇴 배치] userId={} 처리 실패 — 건너뛰고 계속", userId, e);
            }
        }
        log.info("[탈퇴 배치] {} 기준 {}건 삭제", runDate, deleted);
        return deleted;
    }
}
