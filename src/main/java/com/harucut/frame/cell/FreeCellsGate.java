package com.harucut.frame.cell;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.exception.FrameErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 자유 사진 칸 렌더 스위치 (ADR-0012 §3) — 합성 Lambda 가 회전·모양을 그릴 줄 알게 배포되기 전까지 꺼 둔다(기본 off).
 * 옛 Lambda 는 모르는 필드를 무시하고 사각 칸으로 그려 버리므로, 꺼져 있으면 자유 칸 프레임의 행사 게시·개인 합성·참가자 합성을 409 FRAME-008 로 막는다.
 * 배포 순서: Lambda → 이 스위치 on.
 */
@Component
public class FreeCellsGate {

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public FreeCellsGate(@Value("${compose.free-cells.enabled:false}") boolean enabled, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    public void requireRenderable(Frame frame) {
        if (frame.hasFreeCells()) {
            requireEnabled();
        }
    }

    public void requireRenderable(FrameVersion version) {
        if (hasFreeCells(version)) {
            requireEnabled();
        }
    }

    public boolean hasFreeCells(FrameVersion version) {
        return objectMapper.readTree(version.getSpecJson()).path("layout").path("cells").isArray();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(FrameErrorCode.FREE_CELLS_NOT_RENDERABLE);
        }
    }
}
