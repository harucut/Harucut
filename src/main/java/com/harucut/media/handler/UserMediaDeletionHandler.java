package com.harucut.media.handler;

import com.harucut.auth.service.UserDeletionHandler;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.service.ComposeService;
import com.harucut.storage.service.S3Deleter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UserMediaDeletionHandler implements UserDeletionHandler {

    private final UserMediaRepository userMediaRepository;
    private final ComposeJobRepository composeJobRepository;
    private final S3Deleter s3Deleter;

    // 키를 먼저 모은다(행이 지워지면 못 읽는다) → 행 삭제 → S3는 커밋 후 삭제.
    // 롤백되면 이벤트도 안 나가므로 파일이 억울하게 사라질 일이 없다.
    @Transactional
    @Override
    public void handleUserDeletion(Long userId) {
        List<String> keys = new ArrayList<>();
        keys.addAll(userMediaRepository.findS3KeysByUserId(userId));
        keys.addAll(userMediaRepository.findThumbnailKeysByUserId(userId));
        // 진행 중인 작업까지 — 결과 key 는 작업에서 정해지고, 입력은 확정 때 지워지는데 작업 행이 사라지면 지울 곳이 없다
        for (ComposeJob job : composeJobRepository.findAllByUserId(userId)) {
            keys.add(job.getResultKey());
            keys.add(ComposeService.resultKeyFor(job));
            keys.add(ComposeService.thumbnailKeyFor(job));
            if (job.getStatus() == ComposeStatus.PENDING) {
                keys.addAll(job.inputKeys());
            }
        }

        composeJobRepository.deleteByUserId(userId);
        userMediaRepository.deleteByUserId(userId);

        // 삭제 기록을 남긴다 — S3 가 실패해도 재시도 배치가 지운다. 늦게 끝난 실행이 다시 쓴 결과는 재삭제가 치운다(R5 L498)
        s3Deleter.deleteAfterCommit(keys, ComposeService.LATE_WRITE_WINDOW);
    }
}
