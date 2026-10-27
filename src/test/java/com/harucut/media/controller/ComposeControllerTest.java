package com.harucut.media.controller;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.SecurityConfig;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.dto.ComposeJobResponse;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.exception.MediaErrorCode;
import com.harucut.media.service.ComposeService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(ComposeController.class)
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class,
        JwtTokenService.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("ComposeController")
class ComposeControllerTest extends SecurityBeansMockSupport {

    private static final String BASE_URI = "/api/auth/user/media/compose";
    private static final String PUBLIC_ID = "ComposeUser1";

    private static final String VALID_BODY = """
            {
              "frameId": 7,
              "sourceKeys": ["uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg", "uploads/u/4.jpg"],
              "idempotencyKey": "b7e2c1d0-idem"
            }
            """;

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @MockitoBean
    private ComposeService composeService;

    @Test
    @DisplayName("접수는 202이고 jobId와 PENDING이 실린다")
    void acceptedWithJobId() {
        given(composeService.requestCompose(eq(PUBLIC_ID), any()))
                .willReturn(new ComposeJobResponse(5L, ComposeStatus.PENDING, null, null));

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.ACCEPTED)
                .bodyJson()
                .hasPathSatisfying("$.data.jobId", id -> assertThat(id).isEqualTo(5))
                .hasPathSatisfying("$.data.status", s -> assertThat(s).isEqualTo("PENDING"));
    }

    @Test
    @DisplayName("동시 중복으로 유니크에 지면 승자의 Job 을 202 로 돌려준다 — 500 이 아니다")
    void concurrentDuplicateReplaysWinner() {
        given(composeService.requestCompose(eq(PUBLIC_ID), any()))
                .willThrow(new DataIntegrityViolationException("uk_compose_job_user_idempotency"));
        given(composeService.replayAfterConflict(eq(PUBLIC_ID), any()))
                .willReturn(new ComposeJobResponse(5L, ComposeStatus.PENDING, null, null));

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.ACCEPTED)
                .bodyJson()
                .hasPathSatisfying("$.data.jobId", id -> assertThat(id).isEqualTo(5));
    }

    @Test
    @DisplayName("같은 키에 다른 입력이면 409 MEDIA-001 이다")
    void reusedKeyConflicts() {
        given(composeService.requestCompose(eq(PUBLIC_ID), any()))
                .willThrow(new BusinessException(MediaErrorCode.IDEMPOTENCY_KEY_REUSED));

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("MEDIA-001"));
    }

    @Test
    @DisplayName("원본이 3장이면 400 GEN-003이고 서비스까지 가지 않는다")
    void threeSourcesRejected() {
        String body = """
                {
                  "frameId": 7,
                  "sourceKeys": ["uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg"],
                  "idempotencyKey": "b7e2c1d0-idem"
                }
                """;

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-003"));

        then(composeService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("멱등 key가 없으면 400 GEN-003이다")
    void missingIdempotencyKey() {
        String body = """
                {
                  "frameId": 7,
                  "sourceKeys": ["uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg", "uploads/u/4.jpg"]
                }
                """;

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-003"));

        then(composeService).shouldHaveNoInteractions();
    }

    // 형식을 DTO에서 막는 이유: 렌더러의 parseHexColor는 잘못된 값을 조용히 기본색으로
    // 바꿔 그리므로, 여기서 안 걸리면 오류가 400이 아니라 엉뚱한 색 결과물로 나타난다
    @Test
    @DisplayName("배경색 형식이 #RRGGBB가 아니면 400 GEN-003이고 서비스까지 가지 않는다")
    void malformedBackgroundColorRejected() {
        String body = """
                {
                  "frameId": 7,
                  "sourceKeys": ["uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg", "uploads/u/4.jpg"],
                  "idempotencyKey": "b7e2c1d0-idem",
                  "backgroundColor": "red"
                }
                """;

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-003"));

        then(composeService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("배경색이 #RRGGBB면 202로 접수된다")
    void validBackgroundColorAccepted() {
        given(composeService.requestCompose(eq(PUBLIC_ID), any()))
                .willReturn(new ComposeJobResponse(5L, ComposeStatus.PENDING, null, null));

        String body = """
                {
                  "frameId": 7,
                  "sourceKeys": ["uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg", "uploads/u/4.jpg"],
                  "idempotencyKey": "b7e2c1d0-idem",
                  "backgroundColor": "#FFE4E1"
                }
                """;

        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(accessCookie()))
                .hasStatus(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("폴링에서 DONE이면 mediaId가 실린다")
    void pollDoneCarriesMediaId() {
        given(composeService.getJob(PUBLIC_ID, 5L))
                .willReturn(new ComposeJobResponse(5L, ComposeStatus.DONE, 42L, null));

        assertThat(mockMvc.get().uri(BASE_URI + "/5").cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.status", s -> assertThat(s).isEqualTo("DONE"))
                .hasPathSatisfying("$.data.mediaId", id -> assertThat(id).isEqualTo(42));
    }

    @Test
    @DisplayName("폴링에서 FAILED 면 failureCode 가 실린다 — 화면 문구는 이 값으로 고른다")
    void pollFailedCarriesCode() {
        given(composeService.getJob(PUBLIC_ID, 5L))
                .willReturn(new ComposeJobResponse(5L, ComposeStatus.FAILED, null, "진단용 문구", ComposeFailureCode.SOURCE_MISSING));

        assertThat(mockMvc.get().uri(BASE_URI + "/5").cookie(accessCookie()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.data.failureCode", c -> assertThat(c).isEqualTo("SOURCE_MISSING"));
    }

    @Test
    @DisplayName("남의/없는 Job 폴링은 404 GEN-031이다")
    void hiddenJob() {
        given(composeService.getJob(PUBLIC_ID, 5L))
                .willThrow(new BusinessException(GlobalErrorCode.NOT_FOUND));

        assertThat(mockMvc.get().uri(BASE_URI + "/5").cookie(accessCookie()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("GEN-031"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 AUTH-010이다")
    void unauthenticated() {
        assertThat(mockMvc.post().uri(BASE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("AUTH-010"));

        then(composeService).shouldHaveNoInteractions();
    }

    private Cookie accessCookie() {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService
                .createAccessToken(PUBLIC_ID, UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }
}
