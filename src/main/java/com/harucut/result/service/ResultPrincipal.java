package com.harucut.result.service;

// Authorization: Bearer <resultToken> 으로 인증된 결과 수령자. 이 결과 하나에만 권한이 있다
public record ResultPrincipal(Long resultAccessId, Long composeJobId) {
}
