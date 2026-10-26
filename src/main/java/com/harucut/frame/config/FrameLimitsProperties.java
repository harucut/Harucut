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
        @DefaultValue("7000") int maxSpecChars,
        // 조직 공간당 프레임 수 상한 — 조직 요금제(P4) 전까지 남용만 막는 값. 개인 요금제 한도와 별개
        @DefaultValue("200") int maxFramesPerWorkspace
) {
}
