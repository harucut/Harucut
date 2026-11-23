package com.harucut.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 빈도 제한에 쓰는 클라이언트 IP. 브라우저 → BFF → nginx → 앱 순서라 {@code getRemoteAddr()} 는 BFF 의 출구 IP 다 —
 * 그대로 쓰면 행사 참가자 전원이 한 버킷을 나눠 쓴다.
 *
 * <p>BFF 가 비밀 헤더로 자기를 증명한 요청에서만 BFF 가 넘긴 IP 를 믿는다. X-Forwarded-For 는 쓰지 않는다 — nginx 와
 * Tomcat RemoteIpValve 가 값을 고치기 때문에(판정에 쓴 값을 빼거나 덮어쓴다) 설정에 따라 참가자 IP 가 사라진다.
 * 비밀이 비어 있거나 틀리면 두 헤더를 무시한다(위조로 제한을 피하지 못한다).
 */
@Component
public class ClientIpResolver {

    public static final String SECRET_HEADER = "X-Harucut-Bff-Secret";
    public static final String CLIENT_IP_HEADER = "X-Harucut-Client-Ip";

    // IPv4·IPv6 표기에 쓰는 문자만 — 이 값이 Redis 키에 들어간다
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final byte[] secret;

    public ClientIpResolver(@Value("${security.bff.shared-secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public String resolve(HttpServletRequest request) {
        return trustedClientIp(request);
    }

    private String trustedClientIp(HttpServletRequest request) {
        String presented = request.getHeader(SECRET_HEADER);
        if (secret.length > 0 && presented != null
                && MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
            String value = request.getHeader(CLIENT_IP_HEADER);
            if (value != null) {
                int comma = value.indexOf(',');
                String first = (comma < 0 ? value : value.substring(0, comma)).strip();
                if (IP_LITERAL.matcher(first).matches()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
