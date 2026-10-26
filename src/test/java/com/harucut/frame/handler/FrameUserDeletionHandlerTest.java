package com.harucut.frame.handler;

import com.harucut.draft.service.FrameDraftCleaner;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameVersionCleaner;
import com.harucut.frame.service.FrameComponentAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrameUserDeletionHandler")
class FrameUserDeletionHandlerTest {

    @Mock
    private FrameRepository frameRepository;

    @Mock
    private FrameComponentAssembler frameComponentAssembler;

    @Mock
    private FrameVersionCleaner frameVersionCleaner;

    @Mock
    private FrameDraftCleaner frameDraftCleaner;

    @InjectMocks
    private FrameUserDeletionHandler handler;

    @Test
    @DisplayName("모든 프레임의 키가 모여 커밋 후 삭제로 예약된다")
    void collectsAllKeys() {
        Frame frame1 = mock(Frame.class);
        Frame frame2 = mock(Frame.class);
        given(frameRepository.findAllWithComponentsByUserId(1L)).willReturn(List.of(frame1, frame2));
        given(frameComponentAssembler.collectAllKeys(frame1)).willReturn(List.of("k1", "k2"));
        given(frameComponentAssembler.collectAllKeys(frame2)).willReturn(List.of("k3"));

        handler.handleUserDeletion(1L);

        then(frameComponentAssembler).should().scheduleDeletion(List.of("k1", "k2", "k3"));
    }

    @Test
    @DisplayName("컴포넌트가 프레임보다 먼저 지워진다")
    void deletesComponentsFirst() {
        given(frameRepository.findAllWithComponentsByUserId(1L)).willReturn(List.of());

        handler.handleUserDeletion(1L);

        InOrder order = inOrder(frameRepository);
        order.verify(frameRepository).deleteComponentsByUserId(1L);
        order.verify(frameRepository).deleteByUserId(1L);
    }

    @Test
    @DisplayName("발행본·초안이 프레임보다 먼저 지워진다(FK) — 새 프레임 초안까지 사용자 기준으로")
    void deletesVersionsAndDraftsBeforeFrames() {
        Frame frame = mock(Frame.class);
        given(frame.getId()).willReturn(7L);
        given(frameRepository.findAllWithComponentsByUserId(1L)).willReturn(List.of(frame));

        handler.handleUserDeletion(1L);

        InOrder order = inOrder(frameVersionCleaner, frameDraftCleaner, frameRepository);
        order.verify(frameVersionCleaner).deleteForFrames(List.of(7L));
        order.verify(frameDraftCleaner).deleteForUser(1L);
        order.verify(frameRepository).deleteByUserId(1L);
    }
}
