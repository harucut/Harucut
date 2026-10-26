package com.harucut.event.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.event.dto.InheritableSettings;
import com.harucut.event.dto.PageTheme;
import com.harucut.event.service.EffectiveSettingsResolver.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EffectiveSettingsResolver — 공간 ⊕ 프로젝트 ⊕ 행사")
class EffectiveSettingsResolverTest {

    private final EffectiveSettingsResolver resolver = new EffectiveSettingsResolver(JsonMapper.builder().build());

    private static final String WORKSPACE = """
            {"timezone":"Asia/Tokyo","resultRetentionDays":60,"allowedLayouts":["CLASSIC","GRID"],
             "consentItems":[{"code":"ws","version":1,"title":"공간 동의","required":true,"body":"b"}],
             "theme":{"colors":{"bg":"#111111","accent":"#222222"},"copy":{"welcome":"공간 환영"},"logoAssetId":"WsLogo"}}""";
    private static final String PROJECT = """
            {"resultRetentionDays":45,"theme":{"colors":{"bg":"#333333"},"copy":{"done":"프로젝트 완료"}}}""";

    @Test
    @DisplayName("뒤 단계가 이기고 경로마다 출처를 준다 — 테마는 하위 키 단위, 값 없는 경로는 sources 에 없다")
    void laterLevelWinsPerPath() {
        EffectiveSettingsResolver.Effective effective = resolver.resolve(WORKSPACE, PROJECT,
                "{\"boothEnabled\":true,\"theme\":{\"copy\":{\"welcome\":\"행사 환영\"}}}");

        assertThat(effective.values().timezone()).isEqualTo("Asia/Tokyo");
        assertThat(effective.resultRetentionDays()).isEqualTo(45);
        assertThat(effective.boothEnabled()).isTrue();
        PageTheme theme = effective.values().theme();
        assertThat(theme.colors().bg()).isEqualTo("#333333");
        assertThat(theme.colors().accent()).isEqualTo("#222222");
        assertThat(theme.copy().welcome()).isEqualTo("행사 환영");
        assertThat(theme.copy().done()).isEqualTo("프로젝트 완료");
        assertThat(effective.sources()).containsEntry("timezone", Source.WORKSPACE)
                .containsEntry("resultRetentionDays", Source.PROJECT)
                .containsEntry("boothEnabled", Source.EVENT)
                .containsEntry("theme.colors.bg", Source.PROJECT)
                .containsEntry("theme.colors.accent", Source.WORKSPACE)
                .containsEntry("theme.copy.welcome", Source.EVENT)
                .containsEntry("allowedLayouts", Source.WORKSPACE)
                .doesNotContainKeys("byodEnabled", "frames", "theme.font");
        assertThat(effective.byodEnabled()).isTrue();   // 어디에도 없으면 서버 기본값
    }

    @Test
    @DisplayName("목록은 통째로 대체된다 — 빈 목록도 직접 값이다")
    void listsReplaceWhole() {
        EffectiveSettingsResolver.Effective effective = resolver.resolve(WORKSPACE, null, "{\"allowedLayouts\":[],\"consentItems\":null}");

        assertThat(effective.values().allowedLayouts()).isEmpty();
        assertThat(effective.sources()).containsEntry("allowedLayouts", Source.EVENT);
        assertThat(effective.values().consentItems()).extracting(c -> c.code()).containsExactly("ws");   // null = 상속
        assertThat(effective.sources()).containsEntry("consentItems", Source.WORKSPACE);
    }

    @Test
    @DisplayName("문구·색 \"\" 은 위 값 대신 FE 기본값, 자산 \"\" 은 이미지 없음 — 유효값에는 키가 없고 출처는 그 단계")
    void emptyStringOverridesToDefault() {
        EffectiveSettingsResolver.Effective effective = resolver.resolve(WORKSPACE, PROJECT,
                "{\"theme\":{\"colors\":{\"bg\":\"\"},\"copy\":{\"welcome\":\"\"},\"logoAssetId\":\"\"}}");

        PageTheme theme = effective.values().theme();
        assertThat(theme.colors().bg()).isNull();
        assertThat(theme.colors().accent()).isEqualTo("#222222");
        assertThat(theme.copy().welcome()).isNull();
        assertThat(theme.logoAssetId()).isNull();
        assertThat(effective.sources()).containsEntry("theme.colors.bg", Source.EVENT)
                .containsEntry("theme.copy.welcome", Source.EVENT)
                .containsEntry("theme.logoAssetId", Source.EVENT);
    }

    @Test
    @DisplayName("상위가 없으면 행사 값 그대로, 아무 값도 없으면 서버 기본값과 빈 출처")
    void noParents() {
        assertThat(resolver.resolve(null, null, null).sources()).isEmpty();
        EffectiveSettingsResolver.Effective none = resolver.resolve(null, null, "{}");
        assertThat(none.timezone()).isEqualTo("Asia/Seoul");
        assertThat(none.resultRetentionDays()).isEqualTo(30);
        assertThat(none.values().theme()).isNull();
    }

    @Test
    @DisplayName("검사: 글꼴 \"\"·모르는 시간대·보관 일수 범위 밖은 GEN-002")
    void validation() {
        assertThatThrownBy(() -> EffectiveSettingsResolver.validate(new InheritableSettings(null, null, null, null, null, null, null,
                new PageTheme(null, null, "", null, null, null, null)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EffectiveSettingsResolver.validate(new InheritableSettings("Mars/Base", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> EffectiveSettingsResolver.validate(new InheritableSettings(null, null, null, 0, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }
}
