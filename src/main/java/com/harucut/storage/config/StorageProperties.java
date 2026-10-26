package com.harucut.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 업로드·다운로드 URL 수명과 자산 검증 한도. 모두 기본값이 있어 설정 파일에 없어도 된다.
 * (docs/adr-0006-p1-personal-editing.md §2)
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        @DefaultValue Upload upload,
        @DefaultValue Download download,
        @DefaultValue Asset asset
) {

    public record Upload(
            // 새 자산 API 의 PUT URL 수명. 기존 presigned-upload(24h)는 건드리지 않는다
            @DefaultValue("10m") Duration putTtl,
            // If-None-Match: * 를 서명에 넣을지. 운영 버킷 CORS 에 If-None-Match 가 없으면
            // 브라우저 PUT 이 막히므로, CORS 확인 전에는 끄고 배포할 수 있게 스위치로 둔다
            @DefaultValue("true") boolean conditionalPut
    ) {
    }

    public record Download(
            @DefaultValue("5m") Duration getTtl
    ) {
    }

    public record Asset(
            // 디코드 검증 동시 실행 수 — 908MB 서버에서 큰 이미지 여러 장을 동시에 풀지 않는다
            @DefaultValue("2") int verifyConcurrency,
            // 검증 자리를 기다리는 최대 시간. 넘으면 503 ASSET-006
            @DefaultValue("20s") Duration verifyWait,
            @DefaultValue("40000000") long maxPixels,
            // 어디서도 참조하지 않는 자산을 지우기까지의 유예
            @DefaultValue("7d") Duration orphanAfter
    ) {
    }
}
