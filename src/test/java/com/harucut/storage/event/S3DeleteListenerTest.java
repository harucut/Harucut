package com.harucut.storage.event;

import com.harucut.storage.service.S3DeletionAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3DeleteListener")
class S3DeleteListenerTest {

    @Mock
    private S3DeletionAttempt deletionAttempt;

    private S3DeleteListener listener;

    @BeforeEach
    void setUp() {
        listener = new S3DeleteListener(deletionAttempt, Runnable::run);
    }

    @Test
    @DisplayName("이벤트의 모든 key 를 첫 시도로 넘긴다")
    void attemptsAllKeys() {
        listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png")));

        then(deletionAttempt).should().attemptFirst("uploads/a.png");
        then(deletionAttempt).should().attemptFirst("uploads/b.png");
    }

    @Test
    @DisplayName("하나가 예외여도 나머지는 지우고 예외를 전파하지 않는다 — 실행기 스레드를 죽이지 않는다")
    void bestEffortOnFailure() {
        willThrow(new RuntimeException("DB 장애")).given(deletionAttempt).attemptFirst("uploads/a.png");

        assertThatCode(() -> listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png"))))
                .doesNotThrowAnyException();

        then(deletionAttempt).should().attemptFirst("uploads/b.png");
    }
}
