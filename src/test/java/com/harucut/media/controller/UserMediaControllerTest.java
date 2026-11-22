package com.harucut.media.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.PageResponse;
import com.harucut.config.SecurityConfig;
import com.harucut.media.dto.MediaShareLinkResponse;
import com.harucut.media.dto.UserMediaResponse;
import com.harucut.media.service.UserMediaService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(UserMediaController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("UserMediaController")
class UserMediaControllerTest extends SecurityBeansMockSupport {

    private static final String BASE_URI = "/api/auth/user/media";
    private static final String PUBLIC_ID = "MediaUser001";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private UserMediaService userMediaService;

    @Test
    @DisplayName("목록이 PageResponse 구조로 직렬화된다")
    void listOk() {
        given(userMediaService.getMyMedia(PUBLIC_ID, 0, 10))
                .willReturn(new PageResponse<>(List.of(mediaResponse()), 1, 1, 0, 10));

        assertThat(mockMvc.get().uri(BASE_URI).cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.content[0].mediaId", id -> assertThat(id).isEqualTo(7))
                .hasPathSatisfying("$.data.content[0].viewUrl",
                        url -> assertThat(url).isEqualTo("https://view.example/p.png"))
                // 봉투가 null 필드를 생략한다 — 프론트는 "null 체크"가 아니라 "필드 존재 체크"를 한다
                .doesNotHavePath("$.data.content[0].thumbnailUrl")
                .hasPathSatisfying("$.data.totalElements", total -> assertThat(total).isEqualTo(1));
    }

    @Test
    @DisplayName("잘못된 page는 400 GEN-002로 나간다")
    void invalidPage() {
        given(userMediaService.getMyMedia(PUBLIC_ID, -1, 10))
                .willThrow(new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "page must be 0 or greater."));

        assertThat(mockMvc.get().uri(BASE_URI + "?page=-1").cookie(accessCookie()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-002"));
    }

    @Test
    @DisplayName("다운로드 URL은 data가 문자열 하나다")
    void downloadUrlIsPlainString() {
        given(userMediaService.getDownloadUrl(PUBLIC_ID, 7L)).willReturn("https://signed.example/p.png");

        assertThat(mockMvc.get().uri(BASE_URI + "/7/download-url").cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data", data -> assertThat(data).isEqualTo("https://signed.example/p.png"));
    }

    @Test
    @DisplayName("남의/없는 미디어는 404 GEN-031이다")
    void hiddenMedia() {
        given(userMediaService.getDownloadUrl(PUBLIC_ID, 7L))
                .willThrow(new BusinessException(GlobalErrorCode.NOT_FOUND));

        assertThat(mockMvc.get().uri(BASE_URI + "/7/download-url").cookie(accessCookie()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-031"));
    }

    @Test
    @DisplayName("기간 초과는 403 SUBS-002로 나간다")
    void retentionExpired() {
        given(userMediaService.getDownloadUrl(PUBLIC_ID, 7L))
                .willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED));

        assertThat(mockMvc.get().uri(BASE_URI + "/7/download-url").cookie(accessCookie()))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("SUBS-002"));
    }

    @Nested
    @DisplayName("PATCH /{mediaId}/display-name")
    class UpdateDisplayName {

        @Test
        @DisplayName("수정 응답에 갱신된 미디어가 실린다")
        void updateOk() {
            given(userMediaService.updateDisplayName(PUBLIC_ID, 7L, "휴가 사진"))
                    .willReturn(mediaResponse());

            assertThat(mockMvc.patch().uri(BASE_URI + "/7/display-name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\": \"휴가 사진\"}")
                    .cookie(accessCookie()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.data.mediaId", id -> assertThat(id).isEqualTo(7));
        }

        @Test
        @DisplayName("빈 이름은 400 GEN-003이고 서비스까지 가지 않는다")
        void blankName() {
            assertThat(mockMvc.patch().uri(BASE_URI + "/7/display-name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\": \"  \"}")
                    .cookie(accessCookie()))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-003"));

            then(userMediaService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("256자 이름은 400 GEN-003이다")
        void tooLongName() {
            String body = "{\"displayName\": \"" + "a".repeat(256) + "\"}";

            assertThat(mockMvc.patch().uri(BASE_URI + "/7/display-name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .cookie(accessCookie()))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-003"));

            then(userMediaService).shouldHaveNoInteractions();
        }
    }

    @Test
    @DisplayName("공유 링크는 data.token 하나다 — 남의 사진은 404 GEN-031")
    void shareLink() {
        given(userMediaService.shareLink(PUBLIC_ID, 7L)).willReturn(new MediaShareLinkResponse("tok-7"));
        given(userMediaService.shareLink(PUBLIC_ID, 8L)).willThrow(new BusinessException(GlobalErrorCode.NOT_FOUND));

        assertThat(mockMvc.post().uri(BASE_URI + "/7/share-link").cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.token", token -> assertThat(token).isEqualTo("tok-7"));
        assertThat(mockMvc.post().uri(BASE_URI + "/8/share-link").cookie(accessCookie()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-031"));
    }

    @Test
    @DisplayName("삭제는 200이고 서비스에 위임된다")
    void deleteOk() {
        assertThat(mockMvc.delete().uri(BASE_URI + "/7").cookie(accessCookie()))
                .hasStatusOk();

        then(userMediaService).should().deleteMedia(PUBLIC_ID, 7L);
    }

    @Test
    @DisplayName("토큰이 없으면 401 AUTH-010이다")
    void unauthenticated() {
        assertThat(mockMvc.get().uri(BASE_URI))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("AUTH-010"));

        then(userMediaService).shouldHaveNoInteractions();
    }

    private Cookie accessCookie() {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }

    // thumbnailUrl null = 썸네일 없는 옛 행 — 직렬화 계약(필드 생략)을 listOk가 검증한다
    private static UserMediaResponse mediaResponse() {
        return new UserMediaResponse(7L, "uploads/users/abc/fourcuts/p.png", "이름.png",
                null, "https://view.example/p.png", "https://signed.example/p.png",
                LocalDateTime.of(2026, 7, 20, 10, 0));
    }
}
