package com.harucut.media.compose;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 합성 입력 지문(R5 L484·L490) — 저장된 spec JSON, 입력 key 순서, 출력 계약의 SHA-256.
 * spec 은 자산을 불변 key 로 가리키므로(프레임·배경·서버 문구) 이 값이 같으면 같은 입력이다.
 */
public final class ComposeInputFingerprint {

    private ComposeInputFingerprint() {
    }

    public static String of(String specJson, List<String> sourceKeys, Integer contract) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(contract).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(specJson).getBytes(StandardCharsets.UTF_8));
            for (String key : sourceKeys) {
                digest.update((byte) 0);
                digest.update(key.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
