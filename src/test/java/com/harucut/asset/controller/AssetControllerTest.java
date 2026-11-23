package com.harucut.asset.controller;

import com.harucut.asset.dto.AssetResponse;
import com.harucut.asset.dto.UploadIntentResponse;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.enums.AssetStatus;
import com.harucut.asset.exception.AssetErrorCode;
import com.harucut.asset.service.AssetService;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.SecurityConfig;
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

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(AssetController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("AssetController")
class AssetControllerTest extends SecurityBeansMockSupport {

    private static final String PUBLIC_ID = "AssetUser001";

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private AssetService assetService;

    @Test
    @DisplayName("업로드 의도는 201 이고 URL·헤더·만료가 실린다")
    void createIntent() {
        given(assetService.createIntent(eq(PUBLIC_ID), any())).willReturn(new UploadIntentResponse(
                "As8kLm2Pq9Xz", "https://s3/put", "PUT",
                Map.of("Content-Type", "image/png", "If-None-Match", "*"), Instant.parse("2026-10-26T01:10:00Z")));

        assertThat(mockMvc.post().uri("/api/auth/assets/upload-intents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"purpose": "FRAME_ASSET", "contentType": "PNG", "fileSize": 2048, "filename": "bg.png"}
                        """)
                .cookie(cookie()))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.data.assetId", v -> assertThat(v).isEqualTo("As8kLm2Pq9Xz"))
                .hasPathSatisfying("$.data.headers.If-None-Match", v -> assertThat(v).isEqualTo("*"))
                .hasPathSatisfying("$.data.expiresAt", v -> assertThat(v).isEqualTo("2026-10-26T01:10:00Z"));
    }

    @Test
    @DisplayName("10MB 를 넘는 fileSize 는 400 GEN-003 이고 서비스까지 가지 않는다")
    void oversizedRejectedByValidation() {
        assertThat(mockMvc.post().uri("/api/auth/assets/upload-intents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"purpose": "FRAME_ASSET", "contentType": "PNG", "fileSize": 10485761, "filename": "bg.png"}
                        """)
                .cookie(cookie()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("GEN-003"));
        then(assetService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("검증에 실패하면 422 + 그 코드, 본문 data 에 REJECTED 자산이 실린다")
    void rejectedComplete() {
        AssetResponse rejected = new AssetResponse("As8kLm2Pq9Xz", AssetPurpose.FRAME_ASSET, AssetStatus.REJECTED,
                "image/png", "uploads/users/x/assets/As8kLm2Pq9Xz.png", null, null, null, "ASSET-003", null, null);
        given(assetService.complete(PUBLIC_ID, "As8kLm2Pq9Xz"))
                .willReturn(new AssetService.CompleteResult(rejected, AssetErrorCode.INVALID_IMAGE));

        assertThat(mockMvc.post().uri("/api/auth/assets/As8kLm2Pq9Xz/complete").cookie(cookie()))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("ASSET-003"))
                .hasPathSatisfying("$.data.status", v -> assertThat(v).isEqualTo("REJECTED"));
    }

    @Test
    @DisplayName("검증에 성공하면 200 과 VERIFIED")
    void verifiedComplete() {
        AssetResponse verified = new AssetResponse("As8kLm2Pq9Xz", AssetPurpose.FRAME_ASSET, AssetStatus.VERIFIED,
                "image/png", "uploads/users/x/assets/As8kLm2Pq9Xz.png", 2048L, 64, 32, null, "https://s3/get", Instant.parse("2026-10-26T01:05:00Z"));
        given(assetService.complete(PUBLIC_ID, "As8kLm2Pq9Xz"))
                .willReturn(new AssetService.CompleteResult(verified, null));

        assertThat(mockMvc.post().uri("/api/auth/assets/As8kLm2Pq9Xz/complete").cookie(cookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.status", v -> assertThat(v).isEqualTo("VERIFIED"))
                .hasPathSatisfying("$.data.width", v -> assertThat(v).isEqualTo(64))
                .hasPathSatisfying("$.data.key", v -> assertThat(v).isEqualTo("uploads/users/x/assets/As8kLm2Pq9Xz.png"));
    }

    @Test
    @DisplayName("비로그인은 401")
    void unauthenticated() {
        assertThat(mockMvc.get().uri("/api/auth/assets/As8kLm2Pq9Xz")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private Cookie cookie() {
        return new Cookie("accessToken", jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }
}
