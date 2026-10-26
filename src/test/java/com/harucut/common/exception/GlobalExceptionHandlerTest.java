package com.harucut.common.exception;

import com.harucut.terms.service.TermsConsentGuard;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.mock.http.server.reactive.MockServerHttpRequest.post;

@WebMvcTest(controllers = {ExceptionFixtureController.class, ValidatedFixtureController.class})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    // WebMvcConfig 의 약관 동의 인터셉터가 요구한다 — 기본값 false 라 동의 강제는 걸리지 않는다
    @MockitoBean
    private TermsConsentGuard termsConsentGuard;

    @Autowired
    private MockMvcTester mockMvc;

    @Nested
    @DisplayName("BusinessException")
    class Business {

        @Test
        @DisplayName("에러코드의 code/status/message를 그대로 반환한다")
        void mapErrorCode() {
            assertThat(mockMvc.get().uri("/fixture-business"))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-031"))
                    .hasPathSatisfying("$.status", s -> assertThat(s).isEqualTo(404))
                    .hasPathSatisfying("$.message", m -> assertThat(m).isEqualTo("Resource not found."));
        }

        @Test
        @DisplayName("생성자에 넘긴 logMessage는 응답에 노출되지 않는다")
        void logMessageNotExposed() {
            assertThat(mockMvc.get().uri("/fixture/business"))
                    .bodyText().doesNotContain("리소스 없음");
        }
    }

    @Nested
    @DisplayName("Bean Validation (GEN-003)")
    class BodyValidation {

        @Test
        @DisplayName("실패한 필드가 2개면 data 배열에 2개가 담긴다")
        void twoFields() {
            assertThat(mockMvc.post().uri("/fixture/body")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("""
                            {"email": "", "password": "123"}"""))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                    .extractingPath("$.data").asArray().hasSize(2);
        }

        @Test
        @DisplayName("data 항목에 rejectedValue 키가 없다 — 평문 비밀번호 유출 방지")
        void noRejectedValue() {
            assertThat(mockMvc.post().uri("/fixture/body")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email": "", "password": "123"}"""))
                    .bodyJson()
                    .doesNotHavePath("$.data[0].rejectedValue")
                    .doesNotHavePath("$.data[1].rejectedValue");

            assertThat(mockMvc.post().uri("/fixture/body")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email": "", "password": "123"}"""))
                    .bodyText().doesNotContain("123");
        }

        @Test
        @DisplayName("항목에 field와 message가 있다")
        void fieldAndMessage() {
            assertThat(mockMvc.post().uri("/fixture/body")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"email": "", "password": "12345678"}"""))
                    .bodyJson()
                    .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("email"))
                    .hasPath("$.data[0].message");
        }
    }

    @Nested
    @DisplayName("파라미터 관련")
    class Parameters {

        @Test
        @DisplayName("타입 변환 실패 → GEN-005")
        void typeMismatch() {
            assertThat(mockMvc.get().uri("/fixture/type-mismatch?value=abc"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-005");
        }

        @Test
        @DisplayName("필수 파라미터 누락 → GEN-004")
        void missingParam() {
            assertThat(mockMvc.get().uri("/fixture/required-param"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-004");
        }

        @Test
        @DisplayName("필수 쿠키 누락 → GEN-004")
        void missingCookie() {
            assertThat(mockMvc.get().uri("/fixture/required-cookie"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-004");
        }

        @Test
        @DisplayName("@Validated 없는 컨트롤러의 제약조건 위반 → GEN-002")
        void withoutValidatedAnnotation() {
            assertThat(mockMvc.get().uri("/fixture/param-validation?size=0"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-002");
        }

        @Test
        @DisplayName("@Validated 있는 컨트롤러의 제약조건 위반 → GEN-002")
        void withValidatedAnnotation() {
            assertThat(mockMvc.get().uri("/fixture-validated/param-validation?size=0"))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-002");
        }
    }

    @Nested
    @DisplayName("요청 형식")
    class RequestFormat {

        @Test
        @DisplayName("깨진 JSON 본문 → GEN-006")
        void brokenJson() {
            assertThat(mockMvc.post().uri("/fixture/body", "{\"email\": "))
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-006");
        }

        @Test
        @DisplayName("잘못된 HTTP 메서드 → GEN-041")
        void methodNotAllowed() {
            assertThat(mockMvc.post().uri("/fixture/ok"))
                    .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-041");
        }

        @Test
        @DisplayName("지원하지 않는 Content-Type → GEN-051")
        void unsupportedMediaType() {
            assertThat(mockMvc.post().uri("/fixture/body")
                    .contentType(MediaType.TEXT_PLAIN).content("hello"))
                    .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-051");
        }

        @Test
        @DisplayName("존재하지 않는 URL → GEN-031")
        void notFound() {
            assertThat(mockMvc.get().uri("/fixture/does-not-exist"))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson().extractingPath("$.code").isEqualTo("GEN-031");
        }
    }

    @Test
    @DisplayName("예상하지 못한 예외 → GEN-091, 원인 메시지는 노출되지 않는다")
    void unexpected() {
        assertThat(mockMvc.get().uri("/fixture/boom"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson().extractingPath("$.code").isEqualTo("GEN-091");

        assertThat(mockMvc.get().uri("/fixture/boom"))
                .bodyText().doesNotContain("예상하지 못한 오류");
    }
}