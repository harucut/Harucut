package com.harucut.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 웹 프론트 출처 목록. CORS 허용 출처와 쿠키 인증 변경 요청의 출처 검사(OriginCheckFilter)가 같은 값을 읽는다 —
 * 한쪽만 고치면 "CORS 는 통과했는데 403" 이 난다.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(CorsProperties::normalize)
                .toList();
    }

    /** 뒤 슬래시를 떼고 소문자로 — Origin 헤더는 경로 없이 scheme://host[:port] 다. */
    static String normalize(String origin) {
        String trimmed = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
