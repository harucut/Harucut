package com.harucut.frame.cell;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameVersion;
import com.harucut.frame.exception.FrameErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("자유 사진 칸 렌더 스위치")
class FreeCellsGateTest {

    @Test
    @DisplayName("꺼져 있으면 자유 칸 프레임·발행본은 409 FRAME-008, 고정 레이아웃은 통과 — 켜면 모두 통과")
    void gate() {
        Frame free = mock(Frame.class);
        given(free.hasFreeCells()).willReturn(true);
        Frame fixed = mock(Frame.class);
        FrameVersion freeVersion = version("{\"layout\":{\"frameType\":\"CLASSIC\",\"slots\":[],\"cells\":[]}}");
        FrameVersion fixedVersion = version("{\"layout\":{\"frameType\":\"CLASSIC\",\"slots\":[]}}");

        FreeCellsGate off = new FreeCellsGate(false, JsonMapper.builder().build());
        assertThatThrownBy(() -> off.requireRenderable(free)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.FREE_CELLS_NOT_RENDERABLE);
        assertThatThrownBy(() -> off.requireRenderable(freeVersion)).isInstanceOf(BusinessException.class);
        assertThatCode(() -> off.requireRenderable(fixed)).doesNotThrowAnyException();
        assertThatCode(() -> off.requireRenderable(fixedVersion)).doesNotThrowAnyException();

        FreeCellsGate on = new FreeCellsGate(true, JsonMapper.builder().build());
        assertThatCode(() -> on.requireRenderable(free)).doesNotThrowAnyException();
        assertThatCode(() -> on.requireRenderable(freeVersion)).doesNotThrowAnyException();
    }

    private static FrameVersion version(String specJson) {
        FrameVersion version = mock(FrameVersion.class);
        given(version.getSpecJson()).willReturn(specJson);
        return version;
    }
}
