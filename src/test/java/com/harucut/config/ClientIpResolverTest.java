package com.harucut.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private static final String SECRET = "bff-secret";
    private static final String REMOTE = "76.76.21.21";

    private final ClientIpResolver resolver = new ClientIpResolver(SECRET);

    private static MockHttpServletRequest request(String secret, String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE);
        if (secret != null) {
            request.addHeader(ClientIpResolver.SECRET_HEADER, secret);
        }
        if (clientIp != null) {
            request.addHeader(ClientIpResolver.CLIENT_IP_HEADER, clientIp);
        }
        return request;
    }

    @Test
    @DisplayName("비밀이 맞으면 BFF 가 넘긴 IP 를 쓴다 — 참가자마다 다른 버킷")
    void trustsWithSecret() {
        assertThat(resolver.resolve(request(SECRET, "203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(request(SECRET, "2001:db8::1"))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("쉼표 목록이면 첫 값 — Vercel 의 x-forwarded-for 를 그대로 넘겨도 된다")
    void firstOfList() {
        assertThat(resolver.resolve(request(SECRET, " 203.0.113.7 , 10.0.0.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("비밀이 없거나 틀리면 헤더를 무시한다 — 위조로 제한을 피하지 못한다")
    void ignoresWithoutSecret() {
        assertThat(resolver.resolve(request(null, "203.0.113.7"))).isEqualTo(REMOTE);
        assertThat(resolver.resolve(request("wrong", "203.0.113.7"))).isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("서버에 비밀을 안 넣었으면 빈 헤더로도 믿지 않는다")
    void blankServerSecretTrustsNothing() {
        assertThat(new ClientIpResolver("").resolve(request("", "203.0.113.7"))).isEqualTo(REMOTE);
    }

    @Test
    @DisplayName("IP 표기가 아닌 값은 버린다 — 이 값이 Redis 키에 들어간다")
    void rejectsNonIp() {
        assertThat(resolver.resolve(request(SECRET, "evil value:*"))).isEqualTo(REMOTE);
        assertThat(resolver.resolve(request(SECRET, "a".repeat(100)))).isEqualTo(REMOTE);
        assertThat(resolver.resolve(request(SECRET, null))).isEqualTo(REMOTE);
    }
}
