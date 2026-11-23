package com.harucut.auth.oauth2.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** 기본 인가 요청에 앱 진입 표시({@code client=app})와 PKCE 값을 속성으로 싣는다 — 세션의 인가 요청과 함께 콜백까지 간다 */
public class AppAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public AppAwareAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return mark(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return mark(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest mark(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved == null || !AppLoginRequest.APP.equals(request.getParameter(AppLoginRequest.CLIENT_PARAM))) {
            return resolved;
        }
        String challenge = AppLoginRequest.validChallenge(request.getParameter(AppLoginRequest.CHALLENGE_PARAM),
                request.getParameter(AppLoginRequest.METHOD_PARAM));
        return OAuth2AuthorizationRequest.from(resolved).attributes(attributes -> {
            attributes.put(AppLoginRequest.CLIENT_ATTRIBUTE, AppLoginRequest.APP);
            if (challenge != null) {
                attributes.put(AppLoginRequest.CHALLENGE_ATTRIBUTE, challenge);
            }
        }).build();
    }
}
