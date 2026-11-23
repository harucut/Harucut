package com.harucut.subscription.dto;

import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.store.StoreAccountTokens;
import com.harucut.subscription.enums.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "내 구독")
public record SubscriptionResponse(

        @Schema(description = """
                **실제로 적용 중인 등급.** 결제 주기가 끝났는데 강등 배치가 아직 안 돌았으면
                `status` 가 `ACTIVE` 여도 여기는 `BASIC` 으로 내려온다.
                **권한 판정은 이 값으로 한다** — 내 정보·사용량 API 와 같은 기준이다.""",
                example = "PLUS")
        PlanTier planTier,

        @Schema(description = """
                구독의 진행 상태. `planTier` 와 달리 **DB 원본**이라 둘이 어긋나 보일 수 있다.

                `ACTIVE` 정상 구독 중 ·
                `CANCELED` 자동갱신 해지 예약(주기 끝까지는 유료) ·
                `PAST_DUE` 정기결제 실패 ·
                `EXPIRED` 만료되어 BASIC 으로 강등됨 ·
                `GRANTED` 쿠폰으로 받은 무료 구독""",
                example = "ACTIVE")
        SubscriptionStatus status,

        @Schema(description = "현재 결제 주기 시작. **BASIC 이면 키 자체가 없다**", example = "2026-07-21T00:00:00")
        LocalDateTime currentPeriodStart,

        @Schema(description = """
                현재 결제 주기 만료. **BASIC 이면 키 자체가 없다.**
                `CANCELED` 상태라면 이 시각까지는 유료 등급이 유지된다.""",
                example = "2026-08-21T00:00:00")
        LocalDateTime currentPeriodEnd,

        @Schema(description = "자동갱신 여부. `false` 면 해지 API 를 다시 부를 수 없다(`SUBS-006`)", example = "true")
        boolean autoRenew,

        @Schema(description = """
                유료 등급의 출처 — 어디서 관리(해지)하는지 안내할 때 쓴다. **BASIC·만료면 키 자체가 없다.**
                `WEB` 웹 정기결제(해지 API) · `APP_STORE` 앱스토어 · `PLAY_STORE` 구글 플레이(각 스토어 구독 관리에서 해지 — 해지 API 는 `SUBS-009`) ·
                `COUPON` 쿠폰 무료 부여""",
                example = "WEB")
        SubscriptionSource source,

        @Schema(description = """
                스토어 구매를 이 계정에 묶는 표시(UUID). 앱이 구매할 때 애플 `appAccountToken`·구글 `obfuscatedAccountId` 로 실어 보낸다 —
                다른 계정의 표시가 실린 구매는 `SUBS-008` 로 거절된다. 계정마다 고정이다.""",
                example = "4a1d6f0e-2b3c-3d4e-9f00-112233445566")
        String storeAccountToken
) {
    public static SubscriptionResponse of(UserSubscription subscription, PlanTier effectiveTier, String userPublicId) {
        return new SubscriptionResponse(
                effectiveTier,
                subscription.getSubscriptionStatus(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.isAutoRenew(),
                effectiveTier == PlanTier.BASIC ? null : subscription.getSource(),
                StoreAccountTokens.of(userPublicId)
        );
    }
}
