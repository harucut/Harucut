package com.harucut.workspace.controller;

import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.SecurityConfig;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.enums.WorkspaceType;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(WorkspaceController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("WorkspaceController")
class WorkspaceControllerTest extends SecurityBeansMockSupport {

    private static final String PUBLIC_ID = "WsUser000001";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private WorkspaceService workspaceService;

    @Test
    @DisplayName("내 공간 목록을 돌려준다")
    void listsMine() {
        given(workspaceService.listMine(PUBLIC_ID)).willReturn(List.of(
                new WorkspaceResponse("Ws1aB2cD3eF4", WorkspaceType.PERSONAL, "내 공간", WorkspaceRole.OWNER)));

        assertThat(mockMvc.get().uri("/api/auth/workspaces").cookie(cookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data[0].workspaceId", v -> assertThat(v).isEqualTo("Ws1aB2cD3eF4"))
                .hasPathSatisfying("$.data[0].type", v -> assertThat(v).isEqualTo("PERSONAL"))
                .hasPathSatisfying("$.data[0].role", v -> assertThat(v).isEqualTo("OWNER"));
    }

    @Test
    @DisplayName("비로그인은 401 이다")
    void unauthenticated() {
        assertThat(mockMvc.get().uri("/api/auth/workspaces")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private Cookie cookie() {
        return new Cookie("accessToken", jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }
}
