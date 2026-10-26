package com.harucut.event.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.InheritableSettings;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 유효 설정 = 공간 기본값 ⊕ 프로젝트 기본값 ⊕ 행사 직접 값 — 뒤가 이기고, 경로 단위로 고른다(ADR-0009 §2-1).
 * 게시와 미리보기(effective-settings)가 이 한 곳을 쓴다 — 둘이 갈라지지 않게.
 */
@Component
public class EffectiveSettingsResolver {

    public enum Source { WORKSPACE, PROJECT, EVENT }

    // 어디에도 값이 없을 때의 서버 기본값(행사 컬럼이 비지 않게). sources 에는 나오지 않는다
    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";
    public static final boolean DEFAULT_BYOD = true;
    public static final boolean DEFAULT_BOOTH = false;
    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int MAX_RETENTION_DAYS = 3650;

    // 최상위 경로 — 목록은 통째로
    static final List<String> TOP = List.of("timezone", "byodEnabled", "boothEnabled", "resultRetentionDays",
            "allowedLayouts", "frames", "consentItems");
    // 테마 하위 경로 — 키마다. "" 를 「기본값·없음」으로 받는 경로(글꼴은 받지 않는다 — 기본은 SYSTEM)
    static final List<String> THEME = List.of("theme.schemaVersion",
            "theme.colors.bg", "theme.colors.card", "theme.colors.text", "theme.colors.muted", "theme.colors.border",
            "theme.colors.accent", "theme.colors.accentContrast",
            "theme.copy.welcome", "theme.copy.consentIntro", "theme.copy.captureGuide", "theme.copy.composing",
            "theme.copy.done", "theme.copy.expired", "theme.copy.closed", "theme.copy.error",
            "theme.font", "theme.logoAssetId", "theme.backgroundAssetId",
            "theme.branding.hideHarucutBranding", "theme.branding.outputLogo");

    private final ObjectMapper objectMapper;

    public EffectiveSettingsResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param sources 값이 있는 경로(「""」로 덮은 경로 포함) → 그 값을 준 단계 */
    public record Effective(InheritableSettings values, Map<String, Source> sources) {

        public String timezone() {
            return values.timezone() != null ? values.timezone() : DEFAULT_TIMEZONE;
        }

        public boolean byodEnabled() {
            return values.byodEnabled() != null ? values.byodEnabled() : DEFAULT_BYOD;
        }

        public boolean boothEnabled() {
            return values.boothEnabled() != null ? values.boothEnabled() : DEFAULT_BOOTH;
        }

        public int resultRetentionDays() {
            return values.resultRetentionDays() != null ? values.resultRetentionDays() : DEFAULT_RETENTION_DAYS;
        }

        public <T> List<T> orEmpty(List<T> list) {
            return list == null ? List.of() : list;
        }
    }

    /** 각 인자는 InheritableSettings JSON(없으면 null) */
    public Effective resolve(String workspaceDefaults, String projectDefaults, String eventDirect) {
        // 이기는 순서 — 행사 → 프로젝트 → 공간
        List<Map.Entry<Source, JsonNode>> levels = List.of(
                Map.entry(Source.EVENT, tree(eventDirect)),
                Map.entry(Source.PROJECT, tree(projectDefaults)),
                Map.entry(Source.WORKSPACE, tree(workspaceDefaults)));
        ObjectNode out = objectMapper.createObjectNode();
        Map<String, Source> sources = new LinkedHashMap<>();
        for (String path : TOP) {
            pick(levels, path).ifPresent(found -> {
                out.set(path, found.getValue());
                sources.put(path, found.getKey());
            });
        }
        for (String path : THEME) {
            pick(levels, path).ifPresent(found -> {
                sources.put(path, found.getKey());
                JsonNode value = found.getValue();
                if (!(value.isString() && value.asString().isEmpty())) {   // "" 는 키 없음(FE 기본값·이미지 없음)
                    put(out, path, value);
                }
            });
        }
        return new Effective(objectMapper.treeToValue(out, InheritableSettings.class), sources);
    }

    // 직접 값·기본값으로 넣고 빼는 키 — 테마는 객체째(그 안에서 빠진 키는 위 단계 값)
    public static final List<String> KEYS = List.of("timezone", "byodEnabled", "boothEnabled", "resultRetentionDays",
            "allowedLayouts", "frames", "consentItems", "theme");

    /**
     * 머지 패치 — patch 에 있는 키만: null 이면 지워서 위 단계 값을 따르고, 값이면 둔다(테마는 객체째). 검사 후 정규화한 JSON(비면 "{}").
     * @param rejectUnknown true 면 상속할 수 없는 키가 있을 때 400(공간·프로젝트 기본값). 행사는 다른 설정 키와 함께 오므로 false
     */
    public String merge(String current, JsonNode patch, boolean rejectUnknown) {
        ObjectNode merged = (ObjectNode) tree(current);
        for (String name : patch.propertyNames()) {
            if (!KEYS.contains(name)) {
                if (rejectUnknown) {
                    throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "기본값으로 둘 수 없는 필드입니다: " + name);
                }
                continue;
            }
            JsonNode value = patch.get(name);
            if (value.isNull()) {
                merged.remove(name);
            } else {
                merged.set(name, value);
            }
        }
        InheritableSettings typed = typed(merged, InheritableSettings.class);
        validate(typed);
        return objectMapper.writeValueAsString(typed);
    }

    /**
     * 받은 JSON 트리를 타입으로 — 맞지 않는 값({"resultRetentionDays":"invalid"} 등)은 400 GEN-002.
     * 본문을 JsonNode 로 받으면 HttpMessageNotReadable 이 아니라 여기서 터지므로, 그대로 두면 500 이 된다
     */
    public <T> T typed(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "값의 형식이 맞지 않습니다: " + e.getOriginalMessage());
        }
    }

    public InheritableSettings read(String json) {
        return objectMapper.treeToValue(tree(json), InheritableSettings.class);
    }

    /** 직접 값·기본값을 저장하기 전 검사 — 형식이 틀리면 400 GEN-002 */
    public static void validate(InheritableSettings settings) {
        if (settings == null) {
            return;
        }
        if (settings.timezone() != null) {
            try {
                ZoneId.of(settings.timezone());
            } catch (DateTimeException | NullPointerException e) {
                throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE, "알 수 없는 시간대입니다: " + settings.timezone());
            }
        }
        Integer days = settings.resultRetentionDays();
        if (days != null && (days < 1 || days > MAX_RETENTION_DAYS)) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "resultRetentionDays 는 1~" + MAX_RETENTION_DAYS + " 이어야 합니다.");
        }
        if (settings.theme() != null && "".equals(settings.theme().font())) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE,
                    "글꼴은 빈 값으로 둘 수 없습니다 — 기본 글꼴은 SYSTEM 입니다.");
        }
    }

    private Optional<Map.Entry<Source, JsonNode>> pick(List<Map.Entry<Source, JsonNode>> levels, String path) {
        for (Map.Entry<Source, JsonNode> level : levels) {
            JsonNode value = at(level.getValue(), path);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                return Optional.of(Map.entry(level.getKey(), value));
            }
        }
        return Optional.empty();
    }

    private JsonNode tree(String json) {
        return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
    }

    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        return node;
    }

    private static void put(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = node.get(parts[i]);
            node = child instanceof ObjectNode object ? object : node.putObject(parts[i]);
        }
        node.set(parts[parts.length - 1], value);
    }
}
