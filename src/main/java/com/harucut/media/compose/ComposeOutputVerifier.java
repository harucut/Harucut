package com.harucut.media.compose;

import com.harucut.storage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 출력 계약 2 의 성공 통지를 믿기 전에 실제 저장된 한 쌍을 대조한다(R5 D09). 통지가 말한 두 체크섬과 S3 가 저장한 두 체크섬이
 * 모두 같아야 한다 — 결과만 있거나, 서로 다른 실행의 바이트가 섞였거나, 검증할 수 없으면 공개하지 않는다.
 * DB 트랜잭션 밖에서 부른다(S3 호출).
 */
@Component
@RequiredArgsConstructor
public class ComposeOutputVerifier {

    private final FileStorageService fileStorageService;

    public boolean verified(ComposeLambdaPayload payload, ComposeLambdaResult result) {
        if (result == null || result.resultSha256() == null || result.thumbnailSha256() == null) {
            return false;
        }
        return same(payload.resultKey(), result.resultSha256()) && same(payload.thumbnailKey(), result.thumbnailSha256());
    }

    private boolean same(String key, String expected) {
        Optional<String> stored = fileStorageService.sha256Checksum(key);
        return stored.isPresent() && stored.get().equals(expected);
    }
}
