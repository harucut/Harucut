package com.harucut.frame.service;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrameAdminService")
class FrameAdminServiceTest {

    private static final Long FRAME_ID = 99L;
    private static final BackgroundAttributes COLOR = new BackgroundAttributes.Color("#FFE4E1");

    @Mock
    private FrameRepository frameRepository;

    @Mock
    private FrameComponentAssembler frameComponentAssembler;

    private FrameAdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new FrameAdminService(frameRepository, frameComponentAssembler);
    }

    @Test
    @DisplayName("생성은 요금제 한도 검사 없이 조립·저장된다 — 정책 의존성 자체가 없다")
    void createsWithoutPolicy() {
        FrameCreateRequest request = request();
        Frame frame = systemFrame();
        FrameResponse expected = response();
        given(frameComponentAssembler.assembleSystem(request)).willReturn(frame);
        given(frameRepository.save(frame)).willReturn(frame);
        given(frameComponentAssembler.toFrameResponse(frame)).willReturn(expected);

        assertThat(adminService.createSystemFrame(request)).isEqualTo(expected);
    }

    @Test
    @DisplayName("목록은 fetch join 쿼리를 그대로 응답으로 변환한다")
    void listsSystemFrames() {
        Frame first = systemFrame();
        Frame second = systemFrame();
        FrameResponse firstResponse = response();
        FrameResponse secondResponse = new FrameResponse(100L, "둘째", "", null, FrameType.WIDE,
                6000, 4000, COLOR, List.of(false, false, false, false), List.of(), true, 0L, null, null, null);
        given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of(first, second));
        given(frameComponentAssembler.toFrameResponse(first)).willReturn(firstResponse);
        given(frameComponentAssembler.toFrameResponse(second)).willReturn(secondResponse);

        assertThat(adminService.listSystemFrames()).containsExactly(firstResponse, secondResponse);
    }

    // ── fixtures ──────────────────────────────

    private static Frame systemFrame() {
        Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC, COLOR);
        ReflectionTestUtils.setField(frame, "id", FRAME_ID);
        return frame;
    }

    private static FrameCreateRequest request() {
        return new FrameCreateRequest("기본", null, "uploads/p.png", FrameType.CLASSIC, null, null,
                COLOR, null, null);
    }

    private static FrameResponse response() {
        return new FrameResponse(FRAME_ID, "기본", "설명", "https://preview", FrameType.CLASSIC,
                2000, 6000, COLOR, List.of(false, false, false, false), List.of(), true, 0L, null, null, null);
    }
}
