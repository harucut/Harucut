package com.harucut.participant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 행사 참가 기본값 (ADR-0007 §10). 현장 측정으로 조정한다 */
@ConfigurationProperties(prefix = "event.participation")
public record ParticipationProperties(
        @DefaultValue("12h") Duration byodSessionTtl,
        @DefaultValue("30m") Duration boothSessionTtl,
        @DefaultValue("30m") Duration captureRunTtl,
        // 같은 IP 의 세션 발급·부스 연결 교환·잘못된 결과 토큰 — 분당
        @DefaultValue("30") int publicRequestsPerMinute,
        // 부스 연결 코드 유효 시간과 실패 한도(넘으면 그 연결 거래를 닫는다)
        @DefaultValue("10m") Duration pairingCodeTtl,
        @DefaultValue("5") int pairingMaxAttempts,
        // 행사당 회수되지 않은 부스 기기 수 — 계약·이용권 기반 한도는 P2B
        @DefaultValue("20") int maxBoothDevicesPerEvent
) {
}
