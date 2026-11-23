package com.harucut.auth.oauth2.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 기본(세션) 저장소 그대로이되, 콜백에서 꺼낸 인가 요청을 요청 속성에 남긴다 — 성공·실패 핸들러는 인증 객체만 받아서
 * 앱 진입 표시를 볼 길이 이것뿐이다. 콜백 필터는 처리 맨 앞에서 꺼내므로 사용자가 취소한 오류 응답에도 남는다.
 */
public class RememberingAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate = new HttpSessionOAuth2AuthorizationRequestRepository();

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                                         HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
        if (removed != null) {
            request.setAttribute(AppLoginRequest.REMOVED_REQUEST_ATTRIBUTE, removed);
        }
        return removed;
    }
}
