package com.harucut.storage.service;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.event.S3DeleteEvent;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

// "커밋 후 S3 삭제"의 공용 입구 (frame·media·행사가 함께 쓴다). 아무것도 직접 지우지 않는다 —
// 관리 대상 key만 걸러 호출자 트랜잭션 안에 삭제 예약 행(s3_deletion)을 남기고 이벤트를 발행한다.
// 커밋 후 S3DeleteListener 가 한 번 지우고, 실패하면 S3DeletionRetrier 가 백오프로 다시 지운다. 롤백되면 예약도 사라진다.
// 트랜잭션 밖에서 부르면 리스너는 안 불리지만(fallbackExecution=false) 예약 행은 남아 재시도 배치가 지운다
@Component
@RequiredArgsConstructor
public class S3Deleter {

    private final ApplicationEventPublisher eventPublisher;
    private final S3DeletionRepository deletionRepository;
    private final Clock clock;

    // null·비관리 key·중복은 여기서 걸러서 받는다
    public void deleteAfterCommit(Collection<String> keys) {
        List<String> managed = keys.stream()
                .filter(S3Keys::isManagedKey)
                .distinct()
                .toList();
        if (managed.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 첫 시도는 커밋 직후 리스너 몫 — 배치는 첫 백오프가 지난 뒤에야 본다(둘이 겹쳐 지우지 않게)
        LocalDateTime firstRetry = now.plus(S3DeletionAttempt.BACKOFF.getFirst());
        managed.forEach(key -> deletionRepository.schedule(key, OpaqueTokens.hash(key), firstRetry, now));
        eventPublisher.publishEvent(new S3DeleteEvent(managed));
    }
}
