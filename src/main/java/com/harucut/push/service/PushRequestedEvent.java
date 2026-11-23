package com.harucut.push.service;

/**
 * 커밋 뒤 발송할 푸시 — PushDispatcher 가 받는다. 트랜잭션이 롤백되면 보내지 않는다.
 * 상태 전이를 이긴 트랜잭션에서만 발행할 것 — 재배달·재실행으로 같은 전이가 두 번 시도돼도 두 번 나가지 않는다.
 */
public record PushRequestedEvent(Long userId, PushMessage message) {
}
