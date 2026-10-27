package com.harucut.subscription.store.dto;

import com.harucut.subscription.store.StorePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class StorePurchaseDtos {

    private StorePurchaseDtos() {
    }

    @Schema(name = "StorePurchaseRequest", description = "스토어 구매 등록 — 앱이 스토어 결제를 마친 직후 보낸다")
    public record PurchaseRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StorePlatform platform,
            @NotBlank @Size(max = 150) @Schema(description = "스토어 상품 id", example = "harucut.plus.monthly",
                    requiredMode = Schema.RequiredMode.REQUIRED) String productId,
            @NotBlank @Size(max = 20000) @Schema(description = """
                    구매 증빙 — `APP_STORE`: StoreKit 2 의 `Transaction.jwsRepresentation`(서명된 거래 JWS),
                    `PLAY_STORE`: `purchaseToken`""", requiredMode = Schema.RequiredMode.REQUIRED) String proof
    ) {
    }

    @Schema(name = "StoreRestoreItem")
    public record RestoreItem(
            @NotBlank @Size(max = 150) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productId,
            @NotBlank @Size(max = 20000) @Schema(description = "구매 등록과 같은 증빙", requiredMode = Schema.RequiredMode.REQUIRED) String proof
    ) {
    }

    @Schema(name = "StoreRestoreRequest", description = "구매 복원 — 스토어가 돌려준 이 기기 계정의 구독 구매 전부(최대 20건)")
    public record RestoreRequest(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) StorePlatform platform,
            @NotEmpty @Size(max = 20) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<@Valid RestoreItem> items
    ) {
    }
}
