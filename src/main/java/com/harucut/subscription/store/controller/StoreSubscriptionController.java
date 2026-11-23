package com.harucut.subscription.store.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.store.dto.StorePurchaseDtos;
import com.harucut.subscription.store.service.StoreSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "구독")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/subscriptions/store-purchases")
public class StoreSubscriptionController {

    private final StoreSubscriptionService storeSubscriptionService;

    @Operation(
            summary = "스토어 구매 등록",
            description = """
                    앱이 스토어 결제를 마친 직후 부른다. 서버가 스토어 증빙을 확인해 이 계정에 묶고 구독을 그 구매로 맞춘 뒤 **내 구독**을 돌려준다.
                    구매할 때 `storeAccountToken`(내 구독 응답)을 애플 `appAccountToken`·구글 `obfuscatedAccountId` 로 실어 보내야 한다.

                    이후 갱신·자동갱신 끔·결제 재시도·만료·환불은 스토어 알림이 반영한다. 같은 구매를 다시 보내도 된다(최신 상태로 맞춘다).
                    """)
    @ApiErrors({
            "SUBS-007: 증빙을 확인하지 못함(서명·번들·상품 불일치, 만료·환불된 구매)",
            "SUBS-008: 다른 계정에 묶인 구매(또는 다른 계정 표시가 실린 구매)",
            "SUBS-009: 웹·다른 스토어로 이미 구독 중 — data.source 가 그 출처(WEB·APP_STORE·PLAY_STORE)",
            "SUBS-010: 모르는 상품"
    })
    @PostMapping
    public Response<SubscriptionResponse> purchase(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @Valid @RequestBody StorePurchaseDtos.PurchaseRequest request) {
        return Response.ok(storeSubscriptionService.purchase(principal.publicId(), request));
    }

    @Operation(
            summary = "스토어 구매 복원",
            description = """
                    스토어가 돌려준 이 기기 계정의 구독 구매를 보낸다. 확인되고 아직 유효한 구매를 이 계정에 묶고, 그중 가장 늦게 끝나는 것으로
                    구독을 맞춘 뒤 **내 구독**을 돌려준다. 확인 실패·만료·모르는 상품은 건너뛴다.

                    - 복원할 것이 없으면 200 으로 지금 구독을 그대로 돌려준다(`planTier` 로 판단).
                    - 유효한 구매가 모두 다른 계정 것이면 `SUBS-008` — 조용히 옮기지 않는다.
                    """)
    @ApiErrors({
            "SUBS-008: 유효한 구매가 모두 다른 계정 것",
            "SUBS-009: 웹·다른 스토어로 이미 구독 중 — data.source"
    })
    @PostMapping("/restore")
    public Response<SubscriptionResponse> restore(@AuthenticationPrincipal AuthenticatedUser principal,
                                                  @Valid @RequestBody StorePurchaseDtos.RestoreRequest request) {
        return Response.ok(storeSubscriptionService.restore(principal.publicId(), request));
    }
}
