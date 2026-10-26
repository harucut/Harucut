package com.harucut.auth.oauth2.app;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.util.Map;

/** 앱 로그인 시작 → 콜백을 실제 resolver·저장소로 재현한다 — 핸들러는 그 콜백 요청을 받는다 */
public final class AppLoginFixtures {

    // 독립 구현(파이썬 hashlib)으로 계산한 S256 쌍
    public static final String VERIFIER = "dBjftJeZ4CVP-mJ92K9qpvTDBhxXNGcTdlb4Q-QmuEA";
    public static final String CHALLENGE = "ZdVdmaUSmWS4_5nbrjk1pE-kDyLRyDcVVK3F1t6tm4s";

    public static final InMemoryClientRegistrationRepository REGISTRATIONS = new InMemoryClientRegistrationRepository(
            CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("client").clientSecret("secret").build());

    private AppLoginFixtures() {
    }

    /** 시작 요청의 쿼리(예: client=app, code_challenge=...)로 인가 요청을 만들어 세션에 두고, 그 세션·state 로 돌아온 콜백 요청 */
    public static MockHttpServletRequest callback(Map<String, String> startParams) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest start = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        start.setServletPath("/oauth2/authorization/google");
        start.setSession(session);
        startParams.forEach(start::setParameter);
        OAuth2AuthorizationRequest authorization = new AppAwareAuthorizationRequestResolver(REGISTRATIONS).resolve(start);
        RememberingAuthorizationRequestRepository repository = new RememberingAuthorizationRequestRepository();
        repository.saveAuthorizationRequest(authorization, start, new MockHttpServletResponse());

        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(session);
        callback.setParameter(OAuth2ParameterNames.STATE, authorization.getState());
        callback.setParameter(OAuth2ParameterNames.CODE, "provider-code");
        repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        return callback;
    }
}
