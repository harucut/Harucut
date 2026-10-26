package com.harucut.booth.security;

/** X-Booth-Token 으로 인증된 부스 기기. 업로드·합성·사진 조회 자격은 없다 — 다음 손님 세션을 여는 것까지만 */
public record BoothPrincipal(Long deviceId, String devicePublicId, Long eventId) {
}
