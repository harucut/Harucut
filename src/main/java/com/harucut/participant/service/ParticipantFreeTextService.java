package com.harucut.participant.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.event.dto.EventExperienceDocument;
import com.harucut.event.dto.FreeTextPolicy;
import com.harucut.event.entity.EventExperienceVersion;
import com.harucut.event.repository.EventExperienceVersionRepository;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.version.FrameSpecComposer;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.text.FreeTextException;
import com.harucut.text.FreeTextRejection;
import com.harucut.text.TextRenderer;
import com.harucut.text.TextStyle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 참가자 자유 문구 — 행사 발행본의 정책으로 검사하고 서버가 굽는다(R5 D06). 미리보기와 합성이 이 한 곳을 쓴다.
 * 참가자에게서는 문자열과 스타일 id 만 받는다 — 이미지·key·URL 은 받지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ParticipantFreeTextService {

    private final EventExperienceVersionRepository experienceVersionRepository;
    private final FrameSpecComposer frameSpecComposer;
    private final TextRenderer textRenderer;
    private final ObjectMapper objectMapper;

    /** 구운 문구와 놓일 자리(캔버스 px). spec 은 그 프레임 발행본의 합성 스펙 — 레이어를 얹을 때 다시 만들지 않게 같이 준다 */
    public record Prepared(TextRenderer.Normalized text, TextStyle style, TextRenderer.Rendered rendered,
                           int x, int y, ComposeSpec spec) {
    }

    /**
     * @throws BusinessException PART-007(행사가 자유 문구를 허용하지 않음)·PART-008(문구·스타일 검증 실패)
     */
    public Prepared prepare(Long experienceVersionId, FrameVersion version, ParticipantDtos.FreeText freeText) {
        FreeTextPolicy policy = policy(experienceVersionId);
        TextStyle style = TextStyle.byId(freeText.styleId())
                .filter(policy.styles()::contains)
                .orElseThrow(() -> invalid(FreeTextRejection.STYLE_NOT_OFFERED, policy));
        ComposeSpec spec = frameSpecComposer.compose(version);
        FreeTextPolicy.Box box = policy.box();
        int x = (int) Math.round(box.x() * spec.canvasWidth());
        int y = (int) Math.round(box.y() * spec.canvasHeight());
        int width = Math.max(1, Math.min(spec.canvasWidth() - x, (int) Math.round(box.width() * spec.canvasWidth())));
        int height = Math.max(1, Math.min(spec.canvasHeight() - y, (int) Math.round(box.height() * spec.canvasHeight())));
        try {
            TextRenderer.Normalized text = textRenderer.normalize(freeText.text(), style,
                    policy.maxLengthOrDefault(), policy.maxLinesOrDefault());
            return new Prepared(text, style, textRenderer.render(text, style, width, height), x, y, spec);
        } catch (FreeTextException e) {
            throw invalid(e.reason(), policy);
        }
    }

    /**
     * 같은 멱등키 재요청의 문구 대조 — 저장된 스냅샷(정규화된 문구·스타일)과 같은가. 둘 다 없으면 같다.
     * 정규화는 렌더와 같은 규칙으로(줄바꿈·앞뒤 공백 차이는 같은 요청), 정책 한도는 보지 않는다(이미 접수된 문구다)
     */
    public boolean matches(java.util.Optional<com.harucut.asset.entity.Asset> snapshot, ParticipantDtos.FreeText request) {
        if (snapshot.isEmpty() || request == null) {
            return snapshot.isEmpty() && request == null;
        }
        com.harucut.asset.entity.Asset saved = snapshot.get();
        if (!saved.getTextStyleId().equals(request.styleId())) {
            return false;
        }
        try {
            TextStyle style = TextStyle.byId(request.styleId()).orElseThrow();
            return saved.getTextSnapshot().equals(textRenderer.normalize(request.text(), style,
                    Integer.MAX_VALUE, Integer.MAX_VALUE).text());
        } catch (FreeTextException | java.util.NoSuchElementException e) {
            return false;
        }
    }

    private FreeTextPolicy policy(Long experienceVersionId) {
        EventExperienceVersion version = experienceVersionRepository.findById(experienceVersionId)
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.FREE_TEXT_NOT_OFFERED));
        return objectMapper.readValue(version.getSettingsJson(), EventExperienceDocument.class).offeredFreeText()
                .orElseThrow(() -> new BusinessException(ParticipantErrorCode.FREE_TEXT_NOT_OFFERED));
    }

    private static BusinessException invalid(FreeTextRejection reason, FreeTextPolicy policy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason.name());
        data.put("maxLength", policy.maxLengthOrDefault());
        data.put("maxLines", policy.maxLinesOrDefault());
        return new BusinessException(ParticipantErrorCode.FREE_TEXT_INVALID, "자유 문구 거절: " + reason, data);
    }
}
