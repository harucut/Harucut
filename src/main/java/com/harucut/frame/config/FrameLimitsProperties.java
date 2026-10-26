package com.harucut.frame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 프레임 저장·합성의 크기 상한 (ADR-0006 §3). 제품 한도가 아니라 저장 구조(spec_json 8000자)를 지키는 값이다.
 */
@ConfigurationProperties(prefix = "frame.limits")
public record FrameLimitsProperties(
        @DefaultValue("40") int maxLayers,
        // compose_job.spec_json 은 8000자다. 캔버스·슬롯·컷아웃 몫을 남기고 잡는다
        @DefaultValue("7000") int maxSpecChars
) {
}
