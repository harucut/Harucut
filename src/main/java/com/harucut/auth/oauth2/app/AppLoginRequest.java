package com.harucut.auth.oauth2.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 앱(시스템 브라우저)에서 시작한 소셜 로그인 (ADR-0011 §1). 시작 요청 {@code /oauth2/authorization/{provider}?client=app&code_challenge=...}
 * 의 표시를 인가 요청 속성에 실어 두고, 콜백에서 그 인가 요청을 꺼낸 뒤 성공·실패 핸들러가 이 값으로 앱 스킴으로 돌려보낼지 정한다.
 *
 * @param codeChallenge PKCE S256 값(base64url, 43자). 모양이 틀렸거나 없으면 null — 로그인은 진행해도 코드를 주지 않는다
 */
public record AppLoginRequest(String codeChallenge) {

    public static final String CLIENT_PARAM = "client";
    public static final String APP = "app";
    public static final String CHALLENGE_PARAM = "code_challenge";
    public static final String METHOD_PARAM = "code_challenge_method";

    static final String CLIENT_ATTRIBUTE = "harucut.client";
    static final String CHALLENGE_ATTRIBUTE = "harucut.codeChallenge";
    // 콜백에서 저장소가 꺼낸 인가 요청 — 같은 요청 안의 핸들러가 읽는다
    static final String REMOVED_REQUEST_ATTRIBUTE = AppLoginRequest.class.getName() + ".removed";

    // SHA-256(32바이트)의 base64url(패딩 없음)은 정확히 43자
    private static final Pattern S256 = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    // S256 만 — method 는 없거나 S256(plain 은 받지 않는다)
    static String validChallenge(String value, String method) {
        boolean s256 = method == null || "S256".equals(method);
        return s256 && value != null && S256.matcher(value).matches() ? value : null;
    }

    /** 이 콜백 요청이 앱에서 시작한 로그인이면 그 표시 */
    public static Optional<AppLoginRequest> of(HttpServletRequest request) {
        if (!(request.getAttribute(REMOVED_REQUEST_ATTRIBUTE) instanceof OAuth2AuthorizationRequest authorization)
                || !APP.equals(authorization.getAttribute(CLIENT_ATTRIBUTE))) {
            return Optional.empty();
        }
        return Optional.of(new AppLoginRequest(authorization.getAttribute(CHALLENGE_ATTRIBUTE)));
    }
}
