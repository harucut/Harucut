package com.harucut.event.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.event.dto.PageTheme;
import com.harucut.event.dto.ResolvedPageTheme;
import com.harucut.storage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 페이지 테마의 기본값 채우기·자산 URL 바꾸기·게시 검사 (ADR-0008 §5). 공개 행사·부스·결과 응답이 모두 이것을 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ThemeResolver {

    static final Set<String> FONTS = Set.of("SYSTEM", "PRETENDARD", "NANUM_MYEONGJO", "GOWUN_DODUM", "BLACK_HAN_SANS");
    static final Set<String> OUTPUT_LOGOS = Set.of("NONE", "ORGANIZER_LOGO");
    static final int COPY_MAX = 200;
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    // 행사 화면 기본 색 — FE .ev-surface 기본값과 같아야 한다(테마 없는 행사도 서버가 이 값을 채워 내려준다).
    // 일부러 중립색이다: 행사 화면은 하루컷 브랜드를 앞세우지 않는다(R5 18절) — 그래서 강조색이 검정이다
    static final PageTheme.Colors DEFAULT_COLORS =
            new PageTheme.Colors("#F6F6F4", "#FFFFFF", "#1B1B1B", "#5E5E5E", "#DADAD8", "#1B1B1B", "#FFFFFF");

    private final AssetRepository assetRepository;
    private final FileStorageService fileStorageService;

    /** 발행본(또는 결과에 고정된 경험 버전)의 테마 → 화면용. 테마가 없어도 기본값으로 채운다 */
    public ResolvedPageTheme resolve(PageTheme theme) {
        PageTheme source = theme == null ? new PageTheme(1, null, null, null, null, null, null) : theme;
        PageTheme.Colors colors = source.colors() == null ? DEFAULT_COLORS : new PageTheme.Colors(
                or(source.colors().bg(), DEFAULT_COLORS.bg()), or(source.colors().card(), DEFAULT_COLORS.card()),
                or(source.colors().text(), DEFAULT_COLORS.text()), or(source.colors().muted(), DEFAULT_COLORS.muted()),
                or(source.colors().border(), DEFAULT_COLORS.border()), or(source.colors().accent(), DEFAULT_COLORS.accent()),
                or(source.colors().accentContrast(), DEFAULT_COLORS.accentContrast()));
        PageTheme.Branding branding = source.branding();
        return new ResolvedPageTheme(1, colors, or(source.font(), "SYSTEM"), url(source.logoAssetId()),
                url(source.backgroundAssetId()), source.copy(),
                new ResolvedPageTheme.Branding(branding != null && Boolean.TRUE.equals(branding.hideHarucutBranding()),
                        branding == null ? "NONE" : or(branding.outputLogo(), "NONE")));
    }

    /** 게시 검사 — 위반을 모두 모은다. whiteLabelAllowed: 이 행사에 적용되는 이용권에 WHITE_LABEL 기능이 있는가 */
    public List<Map<String, String>> violations(PageTheme theme, Long workspaceId, boolean whiteLabelAllowed) {
        List<Map<String, String>> violations = new ArrayList<>();
        if (theme == null) {
            return violations;
        }
        if (theme.schemaVersion() != null && theme.schemaVersion() != 1) {
            violations.add(violation("theme.schemaVersion", "지원하는 버전은 1 이다"));
        }
        if (theme.colors() != null) {
            PageTheme.Colors c = theme.colors();
            Map<String, String> named = new LinkedHashMap<>();
            named.put("bg", c.bg());
            named.put("card", c.card());
            named.put("text", c.text());
            named.put("muted", c.muted());
            named.put("border", c.border());
            named.put("accent", c.accent());
            named.put("accentContrast", c.accentContrast());
            named.forEach((name, value) -> {
                if (value != null && !HEX.matcher(value).matches()) {
                    violations.add(violation("theme.colors." + name, "#RRGGBB 형식이어야 한다"));
                }
            });
        }
        if (theme.font() != null && !FONTS.contains(theme.font())) {
            violations.add(violation("theme.font", "허용 글꼴이 아니다: " + FONTS));
        }
        checkAsset(theme.logoAssetId(), "theme.logoAssetId", workspaceId, violations);
        checkAsset(theme.backgroundAssetId(), "theme.backgroundAssetId", workspaceId, violations);
        if (theme.copy() != null) {
            PageTheme.Copy copy = theme.copy();
            Map<String, String> texts = new LinkedHashMap<>();
            texts.put("welcome", copy.welcome());
            texts.put("consentIntro", copy.consentIntro());
            texts.put("captureGuide", copy.captureGuide());
            texts.put("composing", copy.composing());
            texts.put("done", copy.done());
            texts.put("expired", copy.expired());
            texts.put("closed", copy.closed());
            texts.put("error", copy.error());
            texts.forEach((name, value) -> {
                if (value != null && value.length() > COPY_MAX) {
                    violations.add(violation("theme.copy." + name, COPY_MAX + "자 이내여야 한다"));
                }
            });
        }
        if (theme.branding() != null) {
            if (theme.branding().outputLogo() != null && !OUTPUT_LOGOS.contains(theme.branding().outputLogo())) {
                violations.add(violation("theme.branding.outputLogo", "NONE · ORGANIZER_LOGO 중 하나여야 한다"));
            }
            if (Boolean.TRUE.equals(theme.branding().hideHarucutBranding()) && !whiteLabelAllowed) {
                violations.add(violation("theme.branding.hideHarucutBranding", "이용권 기능 WHITE_LABEL 이 있어야 숨길 수 있다"));
            }
        }
        return violations;
    }

    private void checkAsset(String assetPublicId, String field, Long workspaceId, List<Map<String, String>> violations) {
        if (assetPublicId == null) {
            return;
        }
        boolean usable = assetRepository.findByPublicId(assetPublicId)
                .filter(asset -> asset.isVerified() && asset.getPurpose() == AssetPurpose.PAGE_THEME_ASSET
                        && Objects.equals(asset.getWorkspaceId(), workspaceId))
                .isPresent();
        if (!usable) {
            violations.add(violation(field, "이 공간의 검증된 PAGE_THEME_ASSET 이 아니다"));
        }
    }

    private String url(String assetPublicId) {
        if (assetPublicId == null) {
            return null;
        }
        return assetRepository.findByPublicId(assetPublicId).filter(Asset::isVerified)
                .map(asset -> fileStorageService.generatePresignedGetUrl(asset.getS3Key()))
                .orElse(null);
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> violation(String field, String message) {
        return Map.of("field", field, "reason", message);
    }
}
