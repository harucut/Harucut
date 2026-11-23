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
        @DefaultValue("20") int maxBoothDevicesPerEvent,
        // 장애 때 새 세션·회차·업로드·합성 접수만 멈춘다(R5 L694, O23) — 이미 접수한 작업의 읽기·확정·정리는 그대로 돈다
        @DefaultValue("true") boolean acceptEnabled,
        // 세션 하나의 남용 상한(R5 L675, O09) — 공개 QR 하나로 주최자 한도와 저장소를 소진하지 못하게. 정상 촬영은 회차당 4장에 재시도 몇 번이다
        @DefaultValue("10") int runsPerMinute,
        @DefaultValue("60") int uploadIntentsPerMinute,
        @DefaultValue("16") int maxUnboundInputsPerRun
) {
}
