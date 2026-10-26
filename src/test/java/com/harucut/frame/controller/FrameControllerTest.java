package com.harucut.frame.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.config.SecurityConfig;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.support.FixedClockConfig;
import com.harucut.support.SecurityBeansMockSupport;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@WebMvcTest(FrameController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("FrameController")
class FrameControllerTest extends SecurityBeansMockSupport {

    private static final String BASE_URI = "/api/auth/user/frame";
    private static final String PUBLIC_ID = "AbCdEf12Gh";

    private static final String VALID_BODY = """
            {
              "title": "봄 여행 4컷",
              "previewKey": "uploads/users/AbCdEf12Gh/webm/preview.png",
              "frameType": "CLASSIC",
              "background": { "type": "COLOR", "value": "#FFE4E1" }
            }
            """;

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private FrameService frameService;

    @Nested
    @DisplayName("POST /api/auth/user/frame")
    class Create {

        @Test
        @DisplayName("생성 응답에 frameId와 파생 캔버스 크기가 실린다 — wire 변경의 자물쇠")
        void createReturnsFrameId() {
            given(frameService.createFrame(eq(PUBLIC_ID), any())).willReturn(frameResponse());

            assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY).cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.frameId", id -> assertThat(id).isEqualTo(1))
                    .hasPathSatisfying("$.data.canvasWidth", w -> assertThat(w).isEqualTo(2000))
                    .hasPathSatisfying("$.data.canvasHeight", h -> assertThat(h).isEqualTo(6000))
                    .hasPathSatisfying("$.data.components[0].zIndex", z -> assertThat(z).isEqualTo(1));
        }

        @Test
        @DisplayName("GRADIENT 배경은 400 GEN-006이고 서비스는 호출되지 않는다")
        void gradientRejected() {
            String body = """
                    {
                      "title": "제목", "previewKey": "uploads/p.png", "frameType": "CLASSIC",
                      "background": { "type": "GRADIENT", "value": "#FFF" }
                    }
                    """;

            assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                    .content(body).cookie(accessCookie()))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-006"));

            then(frameService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("IMAGE 배경에 opacity가 없으면 400 GEN-006이다 — 컴팩트 생성자 거부의 HTTP 매핑")
        void missingOpacityRejected() {
            String body = """
                    {
                      "title": "제목", "previewKey": "uploads/p.png", "frameType": "CLASSIC",
                      "background": { "type": "IMAGE", "key": "uploads/bg.png" }
                    }
                    """;

            assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                    .content(body).cookie(accessCookie()))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-006"));

            then(frameService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("title이 비면 400 GEN-003이고 서비스는 호출되지 않는다")
        void blankTitleRejected() {
            String body = """
                    {
                      "title": " ", "previewKey": "uploads/p.png", "frameType": "CLASSIC",
                      "background": { "type": "COLOR", "value": "#FFF" }
                    }
                    """;

            assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                    .content(body).cookie(accessCookie()))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"));

            then(frameService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("한도 초과는 403 SUBS-003으로 나간다")
        void limitExceeded() {
            given(frameService.createFrame(eq(PUBLIC_ID), any()))
                    .willThrow(new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED));

            assertThat(mockMvc.post().uri(BASE_URI).contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY).cookie(accessCookie()))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-003"));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/user/frame")
    class GetList {

        @Test
        @DisplayName("목록이 data 배열로 직렬화된다")
        void listOk() {
            given(frameService.getMyFrames(PUBLIC_ID)).willReturn(List.of(frameResponse()));

            assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data[0].frameId", id -> assertThat(id).isEqualTo(1))
                    .hasPathSatisfying("$.data[0].isSystem", s -> assertThat(s).isEqualTo(false));
        }

        @Test
        @DisplayName("토큰이 없으면 401 AUTH-010이다")
        void unauthenticated() {
            assertThat(mockMvc.get().uri(BASE_URI))
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("AUTH-010"));

            then(frameService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("GET /api/auth/user/frame/{frameId}")
    class GetOne {

        @Test
        @DisplayName("보관 기간이 지난 프레임은 403 SUBS-002다")
        void retentionExpired() {
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(frameService).getFrame(PUBLIC_ID, 1L);

            assertThat(mockMvc.get().uri(BASE_URI + "/1").cookie(accessCookie()))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-002"));
        }

        @Test
        @DisplayName("소프트 캡에 잘린 프레임은 403 SUBS-003이다")
        void capExceeded() {
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED))
                    .given(frameService).getFrame(PUBLIC_ID, 1L);

            assertThat(mockMvc.get().uri(BASE_URI + "/1").cookie(accessCookie()))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("SUBS-003"));
        }
    }

    @Nested
    @DisplayName("PUT · DELETE /api/auth/user/frame/{frameId}")
    class UpdateAndDelete {

        @Test
        @DisplayName("수정 응답에도 갱신된 프레임 전체가 실린다")
        void updateOk() {
            given(frameService.updateFrame(eq(PUBLIC_ID), eq(1L), any())).willReturn(frameResponse());

            assertThat(mockMvc.put().uri(BASE_URI + "/1").contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY).cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.frameId", id -> assertThat(id).isEqualTo(1));
        }

        @Test
        @DisplayName("삭제는 200이고 서비스에 정확한 인자가 전달된다")
        void deleteOk() {
            assertThat(mockMvc.delete().uri(BASE_URI + "/1").cookie(accessCookie()))
                    .hasStatusOk();

            then(frameService).should().deleteFrame(PUBLIC_ID, 1L);
        }
    }

    private Cookie accessCookie() {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }

    private static FrameResponse frameResponse() {
        return new FrameResponse(1L, "봄 여행 4컷", "설명", "https://preview.example/p.png",
                FrameType.CLASSIC, 2000, 6000, new BackgroundAttributes.Color("#FFE4E1"),
                List.of(false, false, false, false),
                List.of(new FrameResponse.ComponentResponse(10L, ComponentType.PHOTO,
                        "https://photo.example/1.png", "uploads/users/AbCdEf12Gh/components/photo1.png",
                        120.5, 220.0, 360.0, 480.0, 1.0, 0.0, 1, Map.of(),
                        false, null, null, null, null)),
                false, 0L, null);
    }
}
