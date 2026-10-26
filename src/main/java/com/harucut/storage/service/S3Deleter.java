package com.harucut.storage.service;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.event.S3DeleteEvent;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.util.S3Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
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
        // 새로 넣은 예약만 첫 시도로 넘긴다. 이미 있는 key(재시도 중·상한을 넘어 남긴 것)는 그 행의 횟수·다음 시각을 그대로 따른다 —
        // 첫 시도로 다루면 실패했을 때 실패 이력과 상한이 처음부터 다시 시작된다
        List<String> fresh = managed.stream()
                .filter(key -> deletionRepository.schedule(key, OpaqueTokens.hash(key), firstRetry, now) == 1)
                .toList();
        if (!fresh.isEmpty()) {
            eventPublisher.publishEvent(new S3DeleteEvent(fresh));
        }
    }

    /**
     * 업로드를 시작하기 전에 그 key 를 지울 책임을 먼저 남긴다 — 다음 시도는 lease 뒤. 업로드 결과가 DB 에 기록되면 {@link #release},
     * 쓸모없게 됐으면 {@link #deleteHeldAfterCommit}. 그사이 프로세스가 멈추면 lease 가 지나 재시도 배치가 지운다.
     * 업로드 중에 다른 곳이 deleteAfterCommit 으로 같은 key 를 예약해도 이 행이 있어 미리 소모되지 않는다(아직 없는 객체를 지우고 끝나지 않게)
     */
    public void holdForUpload(String key, Duration lease) {
        LocalDateTime now = LocalDateTime.now(clock);
        deletionRepository.schedule(key, OpaqueTokens.hash(key), now.plus(lease), now);
    }

    /** 업로드한 객체가 쓸모 있다고 DB 에 기록할 때 같은 트랜잭션에서 — 지울 책임을 푼다 */
    public void release(String key) {
        deletionRepository.deleteByKeyHash(OpaqueTokens.hash(key));
    }

    /** 업로드 책임으로 미뤄 둔 key 를 지금 지운다(커밋 뒤 첫 시도, 실패하면 재시도). 책임 행이 없으면 새로 예약한다 */
    public void deleteHeldAfterCommit(String key) {
        LocalDateTime now = LocalDateTime.now(clock);
        String keyHash = OpaqueTokens.hash(key);
        if (deletionRepository.makeDue(keyHash, now) == 0) {
            deletionRepository.schedule(key, keyHash, now.plus(S3DeletionAttempt.BACKOFF.getFirst()), now);
        }
        eventPublisher.publishEvent(new S3DeleteEvent(List.of(key)));
    }
}
