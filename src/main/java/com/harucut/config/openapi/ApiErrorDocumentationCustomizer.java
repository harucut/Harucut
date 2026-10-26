package com.harucut.config.openapi;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.config.SecurityPaths;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.result.exception.ResultErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 에러 응답 문서를 한 곳에서 만든다. 이 프로젝트 Swagger 문서의 핵심이다.
 *
 * <p>하는 일 셋.
 * <ol>
 *   <li>{@code PublicPaths} 에 없는 경로면 자물쇠(cookieAuth 또는 bearerAuth)를 붙인다.
 *       <b>인가 규칙과 문서가 같은 목록을 읽으므로 어긋날 수 없다.</b></li>
 *   <li>자물쇠가 붙은 경로에 401/403 을 자동으로 채운다. 62개 엔드포인트에 같은 걸 반복해 적지 않는다.</li>
 *   <li>{@link ApiErrorCollector} 가 실어둔 {@link ApiErrors} 선언을 상태 코드별로 묶어
 *       스키마 + <b>코드별 응답 예시</b>로 렌더한다.</li>
 * </ol>
 *
 * <p>기존 코틀린 구현은 4xx/5xx 의 스키마만 바꿔줬고 <b>에러 코드는 문서에 없었다</b>
 * ({@code @ApiResponse} 214개 중 코드가 적힌 곳 8개). 프론트는 HTTP 상태가 아니라 {@code code} 로
 * 분기하므로, 403 하나에 GEN-021 · SUBS-002 · SUBS-003 이 섞여 있으면 문서만 보고는 구분할 수 없었다.
 * 여기서는 상태 코드 하나에 여러 예시를 달아 Swagger UI 드롭다운으로 고를 수 있게 한다.
 */
@Component
public class ApiErrorDocumentationCustomizer implements GlobalOpenApiCustomizer {

    private static final String JSON = "application/json";
    private static final String ADMIN_PREFIX = "/api/admin/";

    /** 인증이 필요한 모든 경로에 공통으로 붙는 401. 인증 필터가 실제로 내는 코드들이다. */
    private static final List<DocumentedError> UNAUTHORIZED = List.of(
            DocumentedError.of(AuthErrorCode.AUTHENTICATION_FAILED, "토큰이 없거나 인증에 실패"),
            DocumentedError.of(AuthErrorCode.INVALID_TOKEN, "액세스 토큰이 아님 (refresh 토큰을 넣은 경우 포함)"),
            DocumentedError.of(AuthErrorCode.EXPIRED_TOKEN, "만료 — `POST /api/harucut/reissue` 로 재발급 후 재시도"));

    @Override
    public void customise(OpenAPI openApi) {
        ApiErrorSchemas.registerOn(openApi);

        if (openApi.getPaths() == null) {
            return;
        }

        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperations().forEach(operation -> customise(path, operation)));
    }

    private void customise(String path, Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }

        Map<String, DocumentedError> errors = new LinkedHashMap<>();

        if (SecurityPaths.isEvent(path)) {
            // 행사 체인: 회원 쿠키·회원 401/403 이 아니라 경로마다 다른 헤더 자격과 코드
            eventCredential(path, operation, errors);
        } else if (!SecurityPaths.isPublic(path)) {
            requireAuthentication(operation);
            UNAUTHORIZED.forEach(error -> errors.put(error.code(), error));

            if (canBeForbidden(path, operation)) {
                errors.put(GlobalErrorCode.FORBIDDEN.getCode(), forbidden(path));
            }
        }

        ApiErrorCollector.declaredOn(operation).stream()
                .map(DocumentedError::parse)
                .forEach(error -> errors.merge(error.code(), error, DocumentedError::mergedWith));

        render(operation, errors.values());
        fillRemainingErrorSchemas(operation);

        discardCollectorExtension(operation);
    }

    private void eventCredential(String path, Operation operation, Map<String, DocumentedError> errors) {
        if (SecurityPaths.isEventOpen(path)) {
            return;
        }
        if (path.startsWith("/api/participant/")) {
            requireScheme(operation, OpenApiConfig.PARTICIPANT_SCHEME);
            errors.put(ParticipantErrorCode.SESSION_INVALID.getCode(),
                    DocumentedError.of(ParticipantErrorCode.SESSION_INVALID, "참가자 토큰이 없거나 만료·회수"));
        } else if (path.startsWith("/api/public/results")) {
            requireScheme(operation, OpenApiConfig.RESULT_SCHEME);
            errors.put(ResultErrorCode.TOKEN_INVALID.getCode(),
                    DocumentedError.of(ResultErrorCode.TOKEN_INVALID, "결과 토큰이 없거나 틀림"));
        } else if (path.startsWith("/api/booth/")) {
            requireScheme(operation, OpenApiConfig.BOOTH_SCHEME);
            errors.put(BoothErrorCode.DEVICE_UNAVAILABLE.getCode(),
                    DocumentedError.of(BoothErrorCode.DEVICE_UNAVAILABLE, "기기 토큰이 없거나 무효(회수·재연결로 바뀜)"));
        }
    }

    private void requireScheme(Operation operation, String scheme) {
        operation.setSecurity(new ArrayList<>(List.of(new SecurityRequirement().addList(scheme))));
    }

    /** {@link ApiErrorCollector} 가 넘긴 임시 확장 필드는 문서에 남기지 않는다. */
    private void discardCollectorExtension(Operation operation) {
        if (operation.getExtensions() == null) {
            return;
        }
        operation.getExtensions().remove(ApiErrorCollector.EXTENSION_KEY);
        operation.getExtensions().remove(ApiErrorCollector.ROLE_REQUIRED_KEY);
        if (operation.getExtensions().isEmpty()) {
            operation.setExtensions(null);
        }
    }

    /**
     * 쿠키와 Bearer 를 <b>따로</b> 건다. 하나의 {@code SecurityRequirement} 에 둘을 넣으면 AND(둘 다 필요)가
     * 되므로, OR 로 만들려면 항목을 두 개 추가해야 한다.
     */
    private void requireAuthentication(Operation operation) {
        if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
            return;
        }
        operation.addSecurityItem(new SecurityRequirement().addList(OpenApiConfig.COOKIE_SCHEME));
        operation.addSecurityItem(new SecurityRequirement().addList(OpenApiConfig.BEARER_SCHEME));
    }

    /**
     * 로그인만 요구하는 경로는 403 이 나지 않는다. 탈퇴 요청 상태의 사용자가 자기 상태를 확인하고
     * 복구까지 갈 수 있어야 하기 때문이다({@code SecurityPaths.AUTHENTICATED_ONLY}).
     * 다만 그런 경로에도 {@code @PreAuthorize} 가 따로 붙어 있으면 거기서 403 이 난다.
     */
    private boolean canBeForbidden(String path, Operation operation) {
        return !SecurityPaths.isAuthenticatedOnly(path) || ApiErrorCollector.roleRequired(operation);
    }

    private DocumentedError forbidden(String path) {
        String note = path.startsWith(ADMIN_PREFIX)
                ? "ADMIN 권한 없음, 또는 탈퇴 요청(DELETED_REQUESTED)·정지(BLOCKED) 상태"
                : "탈퇴 요청(DELETED_REQUESTED)·정지(BLOCKED) 상태의 사용자";

        return DocumentedError.of(GlobalErrorCode.FORBIDDEN, note);
    }

    private void render(Operation operation, Iterable<DocumentedError> errors) {
        Map<Integer, List<DocumentedError>> byStatus = new TreeMap<>();
        errors.forEach(error -> byStatus.computeIfAbsent(error.status(), key -> new ArrayList<>()).add(error));

        ApiResponses responses = operation.getResponses();
        byStatus.forEach((status, sameStatus) -> responses.addApiResponse(
                String.valueOf(status),
                new ApiResponse()
                        .description(sameStatus.stream()
                                .map(DocumentedError::descriptionLine)
                                .collect(Collectors.joining("\n")))
                        .content(content(sameStatus))));
    }

    private Content content(List<DocumentedError> errors) {
        boolean carriesFieldErrors = errors.stream()
                .anyMatch(error -> GlobalErrorCode.VALIDATION_FAILED.getCode().equals(error.code()));

        MediaType mediaType = new MediaType()
                .schema(new Schema<>().$ref(carriesFieldErrors ? ApiErrorSchemas.VALIDATION_REF : ApiErrorSchemas.ERROR_REF));

        errors.forEach(error -> mediaType.addExamples(error.code(), error.toExample()));

        return new Content().addMediaType(JSON, mediaType);
    }

    /**
     * {@code @ApiResponse} 를 손으로 달았는데 {@code content} 를 안 준 4xx/5xx 를 구제한다.
     * 그대로 두면 springdoc 이 <b>성공 응답 타입</b>으로 에러 스키마를 채운다. 기존 코틀린 구현이
     * {@code OpenApiErrorResponseConfig} 를 만든 이유가 이것이고, 그 방어는 그대로 남긴다.
     */
    private void fillRemainingErrorSchemas(Operation operation) {
        operation.getResponses().forEach((statusCode, response) -> {
            int status;
            try {
                status = Integer.parseInt(statusCode);
            } catch (NumberFormatException e) {
                return;
            }
            if (status < 400 || response.getContent() != null) {
                return;
            }
            response.setContent(new Content()
                    .addMediaType(JSON, new MediaType().schema(new Schema<>().$ref(ApiErrorSchemas.ERROR_REF))));
        });
    }
}
