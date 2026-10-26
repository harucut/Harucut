package com.harucut.frame.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.config.SecurityConfig;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.service.FrameAdminService;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@WebMvcTest(FrameAdminController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("FrameAdminController")
class FrameAdminControllerTest extends SecurityBeansMockSupport {

    private static final String BASE_URI = "/api/admin/frames";
    private static final String ADMIN_PUBLIC_ID = "AdminUser01";

    private static final String VALID_BODY = """
            {
              "title": "기본 프레임",
              "previewKey": "uploads/system/preview.png",
              "frameType": "CLASSIC",
              "background": { "type": "COLOR", "value": "#FFE4E1" }
            }
            """;

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private FrameAdminService frameAdminService;

    @Test
    @DisplayName("생성 응답에 시스템 프레임 전체가 실린다")
    void createOk() {
        given(frameAdminService.createSystemFrame(any())).willReturn(frameResponse());

        assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY).cookie(accessCookie(UserRole.ROLE_ADMIN)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.frameId", id -> assertThat(id).isEqualTo(99))
                .hasPathSatisfying("$.data.isSystem", s -> assertThat(s).isEqualTo(true));
    }

    @Test
    @DisplayName("목록이 data 배열로 직렬화된다")
    void listOk() {
        given(frameAdminService.listSystemFrames()).willReturn(List.of(frameResponse()));

        assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie(UserRole.ROLE_ADMIN)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data[0].frameId", id -> assertThat(id).isEqualTo(99));
    }

    @Test
    @DisplayName("수정은 PUT이다 — 200과 갱신된 프레임을 돌려준다")
    void updateUsesPut() {
        given(frameAdminService.updateSystemFrame(eq(99L), any())).willReturn(frameResponse());

        assertThat(mockMvc.put().uri(BASE_URI + "/99").contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY).cookie(accessCookie(UserRole.ROLE_ADMIN)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.frameId", id -> assertThat(id).isEqualTo(99));
    }

    @Test
    @DisplayName("PATCH는 더 이상 없다 — 405 GEN-041 (PUT 통일의 자물쇠)")
    void patchGone() {
        assertThat(mockMvc.patch().uri(BASE_URI + "/99").contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY).cookie(accessCookie(UserRole.ROLE_ADMIN)))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-041"));

        then(frameAdminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("사용자 프레임 id 조작 시도는 404 FRAME-001로 나간다")
    void userFrameManipulationRejected() {
        willThrow(new BusinessException(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND))
                .given(frameAdminService).deleteSystemFrame(1L);

        assertThat(mockMvc.delete().uri(BASE_URI + "/1").cookie(accessCookie(UserRole.ROLE_ADMIN)))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("FRAME-001"));
    }

    @Test
    @DisplayName("일반 사용자는 403 GEN-021이고 서비스가 호출되지 않는다")
    void forbiddenForUser() {
        assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY).cookie(accessCookie(UserRole.ROLE_USER)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-021"));

        then(frameAdminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("토큰이 없으면 401 AUTH-010이다")
    void unauthenticated() {
        assertThat(mockMvc.get().uri(BASE_URI))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

        then(frameAdminService).shouldHaveNoInteractions();
    }

    private Cookie accessCookie(UserRole role) {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(ADMIN_PUBLIC_ID, role, UserStatus.ACTIVE).value());
    }

    private static FrameResponse frameResponse() {
        return new FrameResponse(99L, "기본 프레임", "설명", "https://preview.example/p.png",
                FrameType.CLASSIC, 2000, 6000, new BackgroundAttributes.Color("#FFE4E1"),
                List.of(false, false, false, false), List.of(), true, 0L, null);
    }
}
