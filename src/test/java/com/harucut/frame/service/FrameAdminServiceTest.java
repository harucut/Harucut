package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.draft.service.FrameDraftCleaner;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrameAdminService")
class FrameAdminServiceTest {

    private static final Long FRAME_ID = 99L;
    private static final BackgroundAttributes COLOR = new BackgroundAttributes.Color("#FFE4E1");

    @Mock
    private FrameRepository frameRepository;

    @Mock
    private FrameVersionCleaner frameVersionCleaner;

    @Mock
    private FrameDraftCleaner frameDraftCleaner;

    @Mock
    private FrameComponentAssembler frameComponentAssembler;

    private FrameAdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new FrameAdminService(frameRepository, frameVersionCleaner, frameDraftCleaner, frameComponentAssembler);
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

    @Nested
    @DisplayName("updateSystemFrame")
    class UpdateSystemFrame {

        @Test
        @DisplayName("시스템 프레임이면 교체 → flush → 응답 조립 순으로 위임한다")
        void replacesContent() {
            Frame frame = systemFrame();
            FrameCreateRequest request = request();
            FrameResponse expected = response();
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameComponentAssembler.toFrameResponse(frame)).willReturn(expected);

            assertThat(adminService.updateSystemFrame(FRAME_ID, request)).isEqualTo(expected);

            InOrder inOrder = inOrder(frameComponentAssembler, frameRepository);
            inOrder.verify(frameComponentAssembler).replaceContent(frame, request);
            inOrder.verify(frameRepository).saveAndFlush(frame);
            inOrder.verify(frameComponentAssembler).toFrameResponse(frame);
        }

        @Test
        @DisplayName("사용자 프레임 id를 넣으면 FRAME-001이다 — 존재를 확인시켜주지 않는 방어")
        void rejectsUserFrame() {
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(userFrame()));

            assertThatThrownBy(() -> adminService.updateSystemFrame(FRAME_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);

            then(frameComponentAssembler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("없는 id도 같은 FRAME-001이다")
        void rejectsMissingFrame() {
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> adminService.updateSystemFrame(FRAME_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteSystemFrame")
    class DeleteSystemFrame {

        @Test
        @DisplayName("수집된 key 전부를 정리 입구에 넘기고 발행본을 먼저 지운 뒤 행을 지운다")
        void deletesWithAllKeys() {
            Frame frame = systemFrame();
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameComponentAssembler.collectAllKeys(frame))
                    .willReturn(List.of("uploads/k1.png", "uploads/bg.png", "uploads/preview.png"));

            adminService.deleteSystemFrame(FRAME_ID);

            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
            then(frameComponentAssembler).should().scheduleDeletion(captor.capture());
            assertThat(captor.getValue())
                    .containsExactly("uploads/k1.png", "uploads/bg.png", "uploads/preview.png");
            then(frameVersionCleaner).should().deleteForFrames(List.of(FRAME_ID));
            then(frameRepository).should().delete(frame);
        }

        @Test
        @DisplayName("사용자 프레임 삭제 시도는 FRAME-001이고 아무것도 지워지지 않는다")
        void rejectsUserFrame() {
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(userFrame()));

            assertThatThrownBy(() -> adminService.deleteSystemFrame(FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);

            then(frameRepository).should(never()).delete(any(Frame.class));
            then(frameVersionCleaner).shouldHaveNoInteractions();
        }
    }

    @Test
    @DisplayName("목록은 fetch join 쿼리를 그대로 응답으로 변환한다")
    void listsSystemFrames() {
        Frame first = systemFrame();
        Frame second = systemFrame();
        FrameResponse firstResponse = response();
        FrameResponse secondResponse = new FrameResponse(100L, "둘째", "", null, FrameType.WIDE,
                6000, 4000, COLOR, List.of(false, false, false, false), List.of(), true, 0L, null);
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

    private static Frame userFrame() {
        User owner = User.localUser("user@harucut.com", "encoded", "하루컷");
        ReflectionTestUtils.setField(owner, "id", 1L);
        Frame frame = Frame.owned(owner, "내 프레임", "설명", "uploads/p.png", FrameType.CLASSIC, COLOR);
        ReflectionTestUtils.setField(frame, "id", FRAME_ID);
        return frame;
    }

    private static FrameCreateRequest request() {
        return new FrameCreateRequest("기본", null, "uploads/p.png", FrameType.CLASSIC, null, null,
                COLOR, null, null);
    }

    private static FrameResponse response() {
        return new FrameResponse(FRAME_ID, "기본", "설명", "https://preview", FrameType.CLASSIC,
                2000, 6000, COLOR, List.of(false, false, false, false), List.of(), true, 0L, null);
    }
}
