package com.harucut.config.openapi;

import com.harucut.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 프론트 계약 검사(harucut_FE scripts/check_backend_contract.py)가 읽는 산출물을 고정한다.
 *
 * 운영 /v3/api-docs 와 대조하면 develop 의 새 API 가 운영 배포 전까지 "백엔드에 없는 경로"로 실패한다.
 * 그래서 이 커밋이 실제로 내는 스펙과 에러 코드 목록을 저장소에 둔다(R5 15절).
 *
 * 파일이 실제와 다르면 실패한다 — API 를 바꾸고 갱신을 잊을 수 없다. 갱신은:
 *   ./gradlew updateApiContract
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("API 계약 산출물 (docs/api/*.generated.json)")
class ApiContractSnapshotTest {

    static final Path SPEC = Path.of("docs/api/openapi.generated.json");
    static final Path ERROR_CODES = Path.of("docs/api/error-codes.generated.json");
    private static final boolean UPDATE = Boolean.getBoolean("apiContract.update");

    // 키를 정렬해 쓴다 — springdoc 의 출력 순서가 바뀌어도 내용이 같으면 diff 가 없다
    private static final ObjectMapper WRITER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("OpenAPI 스펙이 산출물과 같다")
    void specMatchesSnapshot() throws IOException {
        String body = mockMvc.get().uri("/v3/api-docs").exchange().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode spec = objectMapper.readTree(body);
        // 요청 호스트에 따라 바뀌는 값 — 산출물에 남기면 환경마다 diff 가 난다
        if (spec instanceof ObjectNode object) {
            object.remove("servers");
        }
        assertSnapshot(SPEC, render(sortKeys(spec)));
    }

    @Test
    @DisplayName("에러 코드 목록이 산출물과 같다")
    void errorCodesMatchSnapshot() throws IOException {
        List<ErrorCode> codes = new ArrayList<>(ErrorCodeCatalog.all());
        codes.sort(Comparator.comparing(ErrorCode::getCode));

        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (ErrorCode code : codes) {
            array.addObject()
                    .put("code", code.getCode())
                    .put("status", code.getHttpStatus().value())
                    .put("message", code.getMessage());
        }
        assertSnapshot(ERROR_CODES, render(array));
    }

    private void assertSnapshot(Path path, String actual) throws IOException {
        if (UPDATE) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(path))
                .as("%s 가 없다 — ./gradlew updateApiContract 로 만들고 커밋할 것", path)
                .isTrue();
        assertThat(Files.readString(path, StandardCharsets.UTF_8))
                .as("%s 가 실제와 다르다 — API 를 바꿨으면 ./gradlew updateApiContract 후 커밋할 것", path)
                .isEqualTo(actual);
    }

    private static String render(JsonNode node) {
        return WRITER.writeValueAsString(node) + "\n";
    }

    // 객체 키를 재귀적으로 정렬한다. 배열 순서는 의미가 있으므로(태그 표시 순서 등) 그대로 둔다
    private static JsonNode sortKeys(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            object.propertyNames().stream().sorted()
                    .forEach(name -> sorted.set(name, sortKeys(object.get(name))));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            array.forEach(element -> copy.add(sortKeys(element)));
            return copy;
        }
        return node;
    }
}
