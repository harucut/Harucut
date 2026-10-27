package com.harucut.subscription.store;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 구매를 계정에 묶는 표시 — 앱이 구매할 때 애플 appAccountToken(UUID)·구글 obfuscatedExternalAccountId 로 싣는다.
 * 검증된 구매에 다른 계정의 표시가 실려 있으면 그 계정의 구매다(SUBS-008). 비밀이 아니다 — 남의 표시를 실으면 그 계정에 등급을 줄 뿐이다.
 */
public final class StoreAccountTokens {

    private StoreAccountTokens() {
    }

    public static String of(String userPublicId) {
        return UUID.nameUUIDFromBytes(("harucut-store:" + userPublicId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
