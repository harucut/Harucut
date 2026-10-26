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
        listener = new S3DeleteListener(deletionAttempt);
    }

    @Test
    @DisplayName("이벤트의 모든 key 를 첫 시도(실패 0회)로 지운다")
    void deletesAllKeys() {
        listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png")));

        then(deletionAttempt).should().attempt("uploads/a.png", 0);
        then(deletionAttempt).should().attempt("uploads/b.png", 0);
    }

    @Test
    @DisplayName("하나가 예외여도 나머지는 지우고 예외를 전파하지 않는다 — 커밋 후 예외는 '저장됐는데 500'이 된다")
    void bestEffortOnFailure() {
        willThrow(new RuntimeException("DB 장애")).given(deletionAttempt).attempt("uploads/a.png", 0);

        assertThatCode(() -> listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png"))))
                .doesNotThrowAnyException();

        then(deletionAttempt).should().attempt("uploads/b.png", 0);
    }
}
