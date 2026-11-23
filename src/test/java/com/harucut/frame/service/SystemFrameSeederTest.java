package com.harucut.frame.service;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SystemFrameSeeder — 기본 시스템 프레임 4종")
class SystemFrameSeederTest {

    private static final BackgroundAttributes COLOR = new BackgroundAttributes.Color("#FFE4E1");

    @Mock
    private FrameRepository frameRepository;

    @InjectMocks
    private SystemFrameSeeder seeder;

    @Test
    @DisplayName("시스템 프레임이 없으면 종류마다 하나씩 — 단색 배경, 컴포넌트 없음, 소유자·공간 없음")
    void seedsEveryTypeWhenEmpty() {
        given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of());

        seeder.run(null);

        List<Frame> saved = savedFrames(4);
        assertThat(saved).extracting(Frame::getFrameType).containsExactly(FrameType.values());
        assertThat(saved).extracting(Frame::getTitle).containsExactly("세로 4컷", "가로 4컷", "네모 4컷", "즉석사진 4컷");
        assertThat(saved).allSatisfy(frame -> {
            assertThat(frame.isSystem()).isTrue();
            assertThat(frame.getUser()).isNull();
            assertThat(frame.getWorkspaceId()).isNull();
            assertThat(frame.getComponents()).isEmpty();
            assertThat(frame.getPhotoCells()).isNull();
            assertThat(frame.getBackground()).isEqualTo(new BackgroundAttributes.Color("#fafaf7"));
            assertThat(frame.getDescription()).isEmpty();
            // 관리 대상(uploads/)이 아니라 응답에서 presign 하지 않고 삭제 대상도 아니다
            assertThat(frame.getPreviewKey()).isNotBlank().doesNotStartWith("uploads/");
        });
    }

    @Test
    @DisplayName("종류마다 이미 있으면 아무것도 넣지 않는다 — 관리자가 만든 것도 그대로 둔다")
    void leavesExistingFramesAlone() {
        given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of(
                Frame.system("클래식", "", "uploads/a.png", FrameType.CLASSIC, COLOR),
                Frame.system("와이드", "", "uploads/b.png", FrameType.WIDE, COLOR),
                Frame.system("그리드", "", "uploads/c.png", FrameType.GRID, COLOR),
                Frame.system("폴라로이드", "", "uploads/d.png", FrameType.POLAROID, COLOR)));

        seeder.run(null);

        verify(frameRepository, never()).save(any());
    }

    @Test
    @DisplayName("[배경]·[스티커] 소재는 기본 프레임으로 세지 않는다 — 전각 괄호·앞 공백도")
    void materialsDoNotCount() {
        given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of(
                Frame.system("[배경] 웨딩/아이보리", "", "uploads/a.png", FrameType.CLASSIC, COLOR),
                Frame.system("【스티커】 하트", "", "uploads/b.png", FrameType.WIDE, COLOR),
                Frame.system("  ［bg］ 바다", "", "uploads/c.png", FrameType.GRID, COLOR),
                Frame.system("즉석", "", "uploads/d.png", FrameType.POLAROID, COLOR)));

        seeder.run(null);

        assertThat(savedFrames(3)).extracting(Frame::getFrameType)
                .containsExactly(FrameType.CLASSIC, FrameType.WIDE, FrameType.GRID);
    }

    private List<Frame> savedFrames(int count) {
        ArgumentCaptor<Frame> captor = ArgumentCaptor.forClass(Frame.class);
        verify(frameRepository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }
}
