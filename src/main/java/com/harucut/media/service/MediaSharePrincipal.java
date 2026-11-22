package com.harucut.media.service;

// Authorization: Bearer <공유 토큰> 으로 연 받는 사람. 이 공유 링크 하나에만 권한이 있다
public record MediaSharePrincipal(Long mediaShareId, Long mediaId) {
}
