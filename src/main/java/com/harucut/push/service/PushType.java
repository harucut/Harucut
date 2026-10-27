package com.harucut.push.service;

/**
 * 발송 종류와 문구. 앱은 data.type 으로 이동할 화면을 고른다(COMPOSE_* → 사진함, 그 밖 → 마이페이지).
 * 문구는 OS 알림으로 바로 보이므로 서버가 갖는다 — FE 와 맞춘 값이다.
 */
public enum PushType {
    COMPOSE_DONE("사진이 완성됐어요", "하루컷에서 확인해 보세요"),
    COMPOSE_FAILED("사진을 만들지 못했어요", "다시 찍어 주세요"),
    PAYMENT_FAILED("결제에 실패했어요", "결제 수단을 확인해 주세요"),
    SUBSCRIPTION_EXPIRING("구독이 3일 뒤 끝나요", "계속 쓰려면 구독을 이어 주세요");

    private final String title;
    private final String body;

    PushType(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }
}
