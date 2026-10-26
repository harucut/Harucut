package com.harucut.config.openapi;

import com.harucut.config.SecurityPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제로 만들어지는 OpenAPI 문서를 검사한다.
 *
 * <p>단위 테스트가 커스터마이저 하나하나를 보는 것과 달리, 여기서는 springdoc 이 컨트롤러를 훑고
 * 그룹을 나눈 <b>최종 결과물</b>을 본다. 조립 과정에서만 드러나는 문제가 있기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("OpenAPI 문서")
class OpenApiDocumentTest {

    private static final Pattern SCHEMA_REF = Pattern.compile("#/components/schemas/([A-Za-z0-9_]+)");
    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");
    private static final String API_DOCS_URL = "/v3/api-docs";

    @Autowired
    private MultipleOpenApiWebMvcResource openApiResource;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("그룹")
    class Groups {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("세 그룹이 각각 문서를 낸다")
        void eachGroupIsServed(String group) {
            assertThat(document(group).get("paths")).isNotEmpty();
        }

        @Test
        @DisplayName("관리자 API 는 사용자 그룹에 섞이지 않는다 — 프론트가 볼 필요가 없다")
        void adminIsNotInUserGroup() {
            assertThat(document("1-user").get("paths").propertyNames())
                    .noneMatch(path -> path.startsWith("/api/admin/"));
        }
    }

    @Nested
    @DisplayName("태그 — 프론트가 보는 분류")
    class Tags {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("컨트롤러 이름이 그대로 노출되지 않는다")
        void noControllerClassNames(String group) {
            assertThat(usedTags(group)).noneMatch(tag -> tag.endsWith("-controller"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("operation 에 붙은 태그는 전부 OpenApiConfig 에 선언돼 있다 — 오타로 새 섹션이 생기는 걸 막는다")
        void everyTagIsDeclared(String group) {
            List<String> declared = declaredTags(group);

            assertThat(usedTags(group)).allMatch(declared::contains);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("그룹이 쓰지 않는 태그는 문서에 남지 않는다 — 빈 섹션이 안 생긴다")
        void noUnusedTags(String group) {
            List<String> used = usedTags(group);

            assertThat(declaredTags(group)).allMatch(used::contains);
        }

        @Test
        @DisplayName("표시 순서는 가나다순이 아니라 선언한 순서다 — 프론트가 화면을 만드는 순서")
        void declarationOrderIsPreserved() {
            assertThat(declaredTags("1-user"))
                    .startsWith("인증 · 이메일 인증", "인증 · 가입 · 로그인", "인증 · 토큰")
                    .endsWith("쿠폰");
        }

        @Test
        @DisplayName("한 도메인이 컨트롤러 둘로 나뉘어 있어도 태그는 하나다")
        void oneTagPerDomain() {
            // TermsController(조회) + TermsAgreementController(동의) → "약관" 하나
            assertThat(usedTags("1-user")).filteredOn("약관"::equals).hasSize(1);
        }
    }

    @Nested
    @DisplayName("스키마 참조")
    class SchemaReferences {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("끊어진 $ref 가 하나도 없다")
        void noDanglingReferences(String group) {
            JsonNode document = document(group);
            List<String> declared = List.copyOf(document.get("components").get("schemas").propertyNames());

            Matcher matcher = SCHEMA_REF.matcher(document.toString());
            List<String> dangling = new ArrayList<>();
            while (matcher.find()) {
                if (!declared.contains(matcher.group(1))) {
                    dangling.add(matcher.group(1));
                }
            }

            // 문서 전용 에러 스키마를 OpenAPI 빈에 등록하면 그룹 문서에서 사라져 여기가 깨진다.
            assertThat(dangling).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("문서 전용 에러 스키마가 모든 그룹에 들어 있다")
        void errorSchemasArePresent(String group) {
            assertThat(document(group).get("components").get("schemas").propertyNames())
                    .contains("ErrorResponse", "ValidationErrorResponse");
        }
    }

    @Nested
    @DisplayName("자물쇠")
    class SecurityLocks {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1-user", "2-admin", "3-webhook"})
        @DisplayName("SecurityPaths 와 정확히 일치한다 — 인가 규칙과 문서가 어긋날 수 없다")
        void locksMatchPublicPaths(String group) {
            List<String> mismatched = new ArrayList<>();

            forEachOperation(group, (path, method, operation) -> {
                boolean locked = operation.has("security");
                boolean open = SecurityPaths.isPublic(path) || SecurityPaths.isEventOpen(path);
                if (locked == open) {
                    mismatched.add(method + " " + path + (locked ? " (공개인데 잠김)" : " (비공개인데 열림)"));
                }
            });

            assertThat(mismatched).isEmpty();
        }

        @Test
        @DisplayName("잠긴 엔드포인트에는 자격 실패 응답이 빠짐없이 있다 — 401, 부스 기기는 403 BOOTH-003(ADR-0007)")
        void securedOperationsDocumentUnauthorized() {
            List<String> missing = new ArrayList<>();

            forEachOperation("1-user", (path, method, operation) -> {
                if (!operation.has("security")) {
                    return;
                }
                boolean documented = path.startsWith("/api/booth/")
                        ? operation.get("responses").path("403").path("content").toString().contains("BOOTH-003")
                        : operation.get("responses").has("401");
                if (!documented) {
                    missing.add(method + " " + path);
                }
            });

            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("로그인만 요구하는 경로에는 403 이 없다 — 탈퇴 요청 상태가 자기 상태를 못 읽으면 복구 진입로가 사라진다")
        void authenticatedOnlyPathsHaveNoForbidden() {
            JsonNode status = document("1-user").get("paths").get("/api/auth/status").get("get");

            assertThat(status.get("responses").propertyNames()).contains("401").doesNotContain("403");
        }

        @Test
        @DisplayName("역할을 보는 경로에는 403 이 빠짐없이 있다")
        void roleCheckedOperationsDocumentForbidden() {
            List<String> missing = new ArrayList<>();

            forEachOperation("1-user", (path, method, operation) -> {
                // 행사 체인은 회원 역할을 보지 않는다 — 403 은 각 API 가 선언한 도메인 코드뿐
                if (!operation.has("security") || SecurityPaths.isAuthenticatedOnly(path) || SecurityPaths.isEvent(path)) {
                    return;
                }
                if (!operation.get("responses").has("403")) {
                    missing.add(method + " " + path);
                }
            });

            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("행사 경로는 회원 쿠키가 아니라 자기 헤더 자격 하나만 건다")
        void eventPathsUseTheirOwnCredential() {
            List<String> wrong = new ArrayList<>();

            forEachOperation("1-user", (path, method, operation) -> {
                if (!SecurityPaths.isEvent(path) || !operation.has("security")) {
                    return;
                }
                String schemes = operation.get("security").toString();
                String expected = path.startsWith("/api/participant/") ? "participantToken"
                        : path.startsWith("/api/public/results") ? "resultAccess" : "boothToken";
                if (!schemes.contains(expected) || schemes.contains("cookieAuth") || schemes.contains("bearerAuth")) {
                    wrong.add(method + " " + path + " " + schemes);
                }
                if (operation.get("responses").path("401").path("content").toString().contains("AUTH-")) {
                    wrong.add(method + " " + path + " (회원 401 코드)");
                }
            });

            assertThat(wrong).isEmpty();
        }

        @Test
        @DisplayName("401 응답에는 코드별 예시가 실제 응답 모양으로 들어 있다")
        void unauthorizedCarriesExamples() {
            JsonNode example = document("1-user")
                    .get("paths").get("/api/auth/user/info").get("get")
                    .get("responses").get("401")
                    .get("content").get("application/json")
                    .get("examples").get("AUTH-012").get("value");

            assertThat(example.get("code").asString()).isEqualTo("AUTH-012");
            assertThat(example.get("status").asInt()).isEqualTo(401);
            assertThat(example.get("message").asString()).isEqualTo("Expired access token.");
        }
    }

    private List<String> declaredTags(String group) {
        JsonNode tags = document(group).get("tags");
        return tags == null ? List.of() : tags.valueStream().map(tag -> tag.get("name").asString()).toList();
    }

    private List<String> usedTags(String group) {
        List<String> used = new ArrayList<>();
        forEachOperation(group, (path, method, operation) ->
                operation.get("tags").valueStream()
                        .map(JsonNode::asString)
                        .filter(tag -> !used.contains(tag))
                        .forEach(used::add));
        return used;
    }

    private void forEachOperation(String group, OperationVisitor visitor) {
        JsonNode paths = document(group).get("paths");
        paths.propertyNames().forEach(path ->
                HTTP_METHODS.stream()
                        .filter(method -> paths.get(path).has(method))
                        .forEach(method -> visitor.visit(path, method, paths.get(path).get(method))));
    }

    /**
     * 문서를 HTTP 대신 springdoc 빈에서 직접 꺼낸다.
     *
     * <p>{@code @AutoConfigureMockMvc} 를 붙이면 스프링 테스트 컨텍스트 키가 달라져
     * <b>두 번째 애플리케이션 컨텍스트</b>가 뜬다. 두 컨텍스트가 같은 인메모리 H2 를 보는데
     * 테스트 프로파일의 {@code ddl-auto} 가 {@code create-drop} 이라, 나중에 뜬 쪽이 스키마를 지우고
     * 다시 만들면서 먼저 돌던 통합 테스트가 "테이블이 없다"로 깨진다. 다른 통합 테스트들과
     * 똑같이 {@code @SpringBootTest @ActiveProfiles("test")} 만 붙여 컨텍스트를 공유한다.
     */
    private JsonNode document(String group) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API_DOCS_URL + "/" + group);

        byte[] body;
        try {
            body = openApiResource.openapiJson(request, API_DOCS_URL, group, Locale.KOREA);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAPI 문서를 만들지 못했다: " + group, e);
        }

        return objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface OperationVisitor {
        void visit(String path, String method, JsonNode operation);
    }
}
