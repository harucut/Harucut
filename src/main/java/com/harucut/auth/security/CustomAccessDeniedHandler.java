package com.harucut.auth.security;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.enums.UserStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.harucut.common.response.Response;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(GlobalErrorCode.FORBIDDEN.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getOutputStream(), Response.error(deniedCode()));
    }

    /** 정지 계정이면 AUTH-042(화면이 마이페이지로 보낸다), 아니면 GEN-021 */
    public static ErrorCode deniedCode() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user && user.status() == UserStatus.BLOCKED
                ? AuthErrorCode.ACCOUNT_BLOCKED : GlobalErrorCode.FORBIDDEN;
    }
}
