package com.harucut.participant.service;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.event.dto.PageTheme;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.media.compose.ComposeSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 출력 로고(theme.branding.outputLogo = ORGANIZER_LOGO) — 회차의 경험 발행본이 고른 주최자 로고를 완성 이미지 맨 위에 얹는다
 * (R5 3절 「화면 브랜드 표시와 완성 이미지 로고 정책은 각각」). UI 의 hideHarucutBranding 과는 별개다.
 *
 * <p>자리는 고정 규칙 — 미리보기도 같은 규칙으로 그린다: 오른쪽 아래, 가장자리 여백 = 캔버스 너비의 3%,
 * 로고 너비 = 캔버스 너비의 18%(높이는 원본 비율), 높이가 캔버스 높이의 12% 를 넘으면 높이를 12% 로 맞추고 너비를 비율로 줄인다.
 * 기존 레이어로 합성하므로 Lambda 변경이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutputLogoPlacer {

    static final double WIDTH_RATIO = 0.18;
    static final double MAX_HEIGHT_RATIO = 0.12;
    static final double MARGIN_RATIO = 0.03;
    static final String ORGANIZER_LOGO = "ORGANIZER_LOGO";

    private final EventExperienceVersionRepository experienceVersionRepository;
    private final AssetRepository assetRepository;
    private final ObjectMapper objectMapper;

    public ComposeSpec apply(Long experienceVersionId, ComposeSpec spec) {
        return logo(experienceVersionId).map(asset -> withLogo(spec, asset)).orElse(spec);
    }

    private Optional<Asset> logo(Long experienceVersionId) {
        PageTheme theme = experienceVersionRepository.findById(experienceVersionId)
                .map(version -> objectMapper.readValue(version.getSettingsJson(), EventExperienceDocument.class).theme())
                .orElse(null);
        if (theme == null || theme.branding() == null || !ORGANIZER_LOGO.equals(theme.branding().outputLogo())
                || theme.logoAssetId() == null) {
            return Optional.empty();
        }
        Optional<Asset> asset = assetRepository.findByPublicId(theme.logoAssetId())
                .filter(a -> a.isVerified() && a.getPurpose() == AssetPurpose.PAGE_THEME_ASSET
                        && a.getWidth() != null && a.getHeight() != null && a.getWidth() > 0 && a.getHeight() > 0);
        if (asset.isEmpty()) {
            // 게시 때 검사한 로고가 사라졌다 — 합성을 실패시키기보다 로고 없이 만든다(주최자 사진함에서 보인다)
            log.warn("[출력 로고] 쓸 수 없는 로고 자산 — 로고 없이 합성한다: experienceVersionId={} asset={}",
                    experienceVersionId, theme.logoAssetId());
        }
        return asset;
    }

    static ComposeSpec withLogo(ComposeSpec spec, Asset logo) {
        int canvasWidth = spec.canvasWidth();
        int canvasHeight = spec.canvasHeight();
        double aspect = logo.getHeight() / (double) logo.getWidth();
        double width = canvasWidth * WIDTH_RATIO;
        double height = width * aspect;
        if (height > canvasHeight * MAX_HEIGHT_RATIO) {
            height = canvasHeight * MAX_HEIGHT_RATIO;
            width = height / aspect;
        }
        int w = Math.max(1, (int) Math.round(width));
        int h = Math.max(1, (int) Math.round(height));
        int margin = (int) Math.round(canvasWidth * MARGIN_RATIO);
        int top = spec.layers().stream().mapToInt(ComposeSpec.Layer::zIndex).max().orElse(0) + 1;
        List<ComposeSpec.Layer> layers = new ArrayList<>(spec.layers());
        layers.add(new ComposeSpec.Layer(logo.getS3Key(), canvasWidth - margin - w, canvasHeight - margin - h, w, h,
                1.0, 0, top, 1.0));
        return new ComposeSpec(canvasWidth, canvasHeight, spec.background(), spec.slots(), spec.cellCutouts(), layers,
                spec.cells());
    }
}
