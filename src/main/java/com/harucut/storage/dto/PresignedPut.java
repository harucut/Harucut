package com.harucut.storage.dto;

import java.time.Instant;
import java.util.Map;

// 브라우저가 S3 에 그대로 보낼 PUT 의 전부 — URL 과, 서명에 들어가서 빼거나 바꾸면 403 이 나는 헤더
public record PresignedPut(String url, Map<String, String> headers, Instant expiresAt) {
}
