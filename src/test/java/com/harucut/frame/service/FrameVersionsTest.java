package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("FrameVersions — 프레임 낙관적 잠금 (FRAME-002)")
class FrameVersionsTest {

    @Test
    @DisplayName("요청 version 이 다르면 409 FRAME-002 + data.currentVersion")
    void mismatchedVersion() {
        Frame frame = frameAtVersion(3L);

        assertThatThrownBy(() -> FrameVersions.assertExpected(frame, 2L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(FrameErrorCode.VERSION_CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(e.getData()).isEqualTo(Map.of("currentVersion", 3L));
                });
    }

    @Test
    @DisplayName("같거나 생략하면 통과 — 생략은 기존 클라이언트 호환(덮어쓰기)")
    void matchingOrOmitted() {
        Frame frame = frameAtVersion(3L);

        assertThatCode(() -> FrameVersions.assertExpected(frame, 3L)).doesNotThrowAnyException();
        assertThatCode(() -> FrameVersions.assertExpected(frame, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("동시 저장이 flush 에서 충돌하면 FRAME-002 로 바꾼다")
    void concurrentFlushConflict() {
        FrameRepository repository = mock(FrameRepository.class);
        given(repository.saveAndFlush(any())).willThrow(new ObjectOptimisticLockingFailureException(Frame.class, 1L));

        assertThatThrownBy(() -> FrameVersions.flush(repository, frameAtVersion(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.VERSION_CONFLICT);
    }

    private static Frame frameAtVersion(Long version) {
        Frame frame = Frame.system("기본", "", "uploads/p.png", FrameType.CLASSIC, new BackgroundAttributes.Color("#FFFFFF"));
        ReflectionTestUtils.setField(frame, "version", version);
        return frame;
    }
}
