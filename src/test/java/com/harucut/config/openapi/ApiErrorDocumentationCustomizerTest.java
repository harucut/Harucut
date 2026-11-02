package com.harucut.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiErrorDocumentationCustomizer")
class ApiErrorDocumentationCustomizerTest {

    private static final String SECURED = "/api/auth/user/frame/{frameId}";
    private static final String PUBLIC = "/api/notices/{publicId}";

    private ApiErrorDocumentationCustomizer customizer;

    @BeforeEach
    void setUp() {
        customizer = new ApiErrorDocumentationCustomizer();
    }

    @Nested
    @DisplayName("자물쇠")
    class Security {

        @Test
        @DisplayName("PublicPaths 에 없는 경로에는 쿠키·Bearer 를 OR 로 건다")
        void securedPathGetsBothSchemes() {
            Operation operation = customise(SECURED);

            // 항목이 둘로 나뉘어야 OR 다. 한 항목에 스킴 둘을 넣으면 "둘 다 필요"가 된다.
            assertThat(operation.getSecurity()).hasSize(2);
            assertThat(operation.getSecurity()).flatExtracting(SecurityRequirement::keySet)
                    .containsExactly("cookieAuth", "bearerAuth");
        }

        @Test
        @DisplayName("공개 경로에는 자물쇠를 걸지 않는다")
        void publicPathStaysOpen() {
            assertThat(customise(PUBLIC).getSecurity()).isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("인증 에러 자동 부착")
    class DefaultAuthErrors {

        @Test
        @DisplayName("잠긴 경로에는 401 과 403 이 자동으로 붙는다")
        void securedPathGetsAuthErrors() {
            ApiResponses responses = customise(SECURED).getResponses();

            assertThat(exampleCodes(responses, "401")).containsExactly("AUTH-010", "AUTH-011", "AUTH-012");
            assertThat(exampleCodes(responses, "403")).containsExactly("GEN-021", "AUTH-042");
        }

        @Test
        @DisplayName("정지 계정에 열린 경로에는 AUTH-042 를 붙이지 않는다 — 탈퇴 요청 상태의 GEN-021 만 남는다")
        void blockedAllowedPathHasNoBlockedError() {
            assertThat(exampleCodes(customise("/api/auth/user/info").getResponses(), "403")).containsExactly("GEN-021");
            assertThat(exampleCodes(customise("/api/harucut/exit").getResponses(), "403")).containsExactly("GEN-021");
        }

        @Test
        @DisplayName("공개 경로에는 붙지 않는다")
        void publicPathHasNoAuthErrors() {
            assertThat(customise(PUBLIC).getResponses()).doesNotContainKeys("401", "403");
        }

        @Test
        @DisplayName("로그인만 요구하는 경로에는 403 을 붙이지 않는다 — 탈퇴 요청 상태도 호출할 수 있다")
        void authenticatedOnlyPathHasNoForbidden() {
            Operation operation = customise("/api/auth/status");

            assertThat(operation.getResponses()).containsKey("401").doesNotContainKey("403");
        }

        @Test
        @DisplayName("로그인만 요구하는 경로라도 @PreAuthorize 가 있으면 403 이 붙는다")
        void authenticatedOnlyPathWithRoleCheckHasForbidden() {
            OpenAPI openAPI = document("/api/harucut/reactivate", List.of());
            operationOf(openAPI).addExtension(ApiErrorCollector.ROLE_REQUIRED_KEY, Boolean.TRUE);

            customizer.customise(openAPI);

            assertThat(exampleCodes(operationOf(openAPI).getResponses(), "403")).containsExactly("GEN-021", "AUTH-042");
        }

        @Test
        @DisplayName("관리자 경로의 403 설명은 ADMIN 권한을 짚어준다")
        void adminForbiddenMentionsRole() {
            Operation operation = customise("/api/admin/notices");

            assertThat(operation.getResponses().get("403").getDescription()).contains("ADMIN");
        }
    }

    @Nested
    @DisplayName("선언한 에러 렌더링")
    class DeclaredErrors {

        @Test
        @DisplayName("같은 상태 코드의 에러가 한 응답에 예시로 모인다")
        void groupsByStatus() {
            Operation operation = customise(SECURED,
                    "SUBS-002: 보관 기간 초과", "SUBS-003: 개수 초과");

            // 자동으로 붙은 GEN-021·AUTH-042 와 선언한 SUBS-002 · SUBS-003 이 403 하나에 모인다
            assertThat(exampleCodes(operation.getResponses(), "403"))
                    .containsExactly("GEN-021", "AUTH-042", "SUBS-002", "SUBS-003");
        }

        @Test
        @DisplayName("GEN-003 이 있는 400 은 data 를 담을 수 있는 스키마를 쓴다")
        void validationStatusUsesValidationSchema() {
            Operation operation = customise(SECURED, "GEN-003: 요청 본문 검증 실패");

            assertThat(schemaRef(operation.getResponses(), "400")).isEqualTo(ApiErrorSchemas.VALIDATION_REF);
        }

        @Test
        @DisplayName("GEN-003 이 없는 400 은 일반 에러 스키마를 쓴다")
        void plainStatusUsesErrorSchema() {
            Operation operation = customise(SECURED, "PAY-007: BASIC 요금제로는 결제할 수 없다");

            assertThat(schemaRef(operation.getResponses(), "400")).isEqualTo(ApiErrorSchemas.ERROR_REF);
        }

        @Test
        @DisplayName("자동으로 붙은 코드와 같은 코드를 선언하면 사유를 합친다 — 한쪽이 사라지지 않는다")
        void mergesNotesForSameCode() {
            Operation operation = customise(SECURED, "GEN-021: 남의 s3Key 를 지정함");

            assertThat(exampleCodes(operation.getResponses(), "403")).containsExactly("GEN-021", "AUTH-042");
            assertThat(operation.getResponses().get("403").getDescription())
                    .contains("탈퇴 요청")
                    .contains("남의 s3Key");
        }

        @Test
        @DisplayName("수집기가 넘긴 임시 확장 필드는 문서에 남기지 않는다")
        void collectorExtensionIsRemoved() {
            Operation operation = customise(SECURED, "SUBS-002: 보관 기간 초과");

            assertThat(operation.getExtensions()).isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("문서 전용 스키마 등록")
    class SchemaRegistration {

        @Test
        @DisplayName("$ref 가 가리키는 스키마가 문서 안에 실제로 존재한다")
        void refsResolve() {
            OpenAPI openAPI = document(SECURED, List.of());
            customizer.customise(openAPI);

            assertThat(openAPI.getComponents().getSchemas())
                    .containsKeys("ErrorResponse", "ValidationErrorResponse");
        }
    }

    @Nested
    @DisplayName("content 없는 4xx 구제")
    class LeftoverErrorResponses {

        @Test
        @DisplayName("손으로 단 @ApiResponse 에 스키마가 없으면 에러 스키마로 채운다")
        void fillsMissingContent() {
            OpenAPI openAPI = document(SECURED, List.of());
            operationOf(openAPI).getResponses()
                    .addApiResponse("409", new ApiResponse().description("충돌"));

            customizer.customise(openAPI);

            assertThat(schemaRef(operationOf(openAPI).getResponses(), "409")).isEqualTo(ApiErrorSchemas.ERROR_REF);
        }
    }

    private Operation customise(String path, String... declaredErrors) {
        OpenAPI openAPI = document(path, List.of(declaredErrors));
        customizer.customise(openAPI);
        return operationOf(openAPI);
    }

    private OpenAPI document(String path, List<String> declaredErrors) {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK")));

        if (!declaredErrors.isEmpty()) {
            operation.addExtension(ApiErrorCollector.EXTENSION_KEY, declaredErrors);
        }

        return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().get(operation)));
    }

    private Operation operationOf(OpenAPI openAPI) {
        return openAPI.getPaths().values().iterator().next().getGet();
    }

    private List<String> exampleCodes(ApiResponses responses, String status) {
        return List.copyOf(mediaType(responses, status).getExamples().keySet());
    }

    private String schemaRef(ApiResponses responses, String status) {
        return mediaType(responses, status).getSchema().get$ref();
    }

    private MediaType mediaType(ApiResponses responses, String status) {
        return responses.get(status).getContent().get("application/json");
    }
}
