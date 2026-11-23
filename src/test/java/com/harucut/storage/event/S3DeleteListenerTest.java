package com.harucut.storage.event;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.entity.S3Deletion;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.service.S3DeletionAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3DeleteListener")
class S3DeleteListenerTest {

    private static final LocalDateTime DUE = LocalDateTime.of(2026, 11, 23, 10, 5);

    @Mock
    private S3DeletionRepository repository;

    @Mock
    private S3DeletionAttempt deletionAttempt;

    private S3DeleteListener listener;

    @BeforeEach
    void setUp() {
        listener = new S3DeleteListener(repository, deletionAttempt, Runnable::run);
    }

    @Test
    @DisplayName("key 마다 지금 예약 행의 횟수·일정으로 시도한다 — 행이 없으면(이미 끝났다) 건너뛴다")
    void attemptsWithCurrentRow() {
        S3Deletion a = row("uploads/a.png", 2);
        given(repository.findByKeyHash(OpaqueTokens.hash("uploads/a.png"))).willReturn(Optional.of(a));
        given(repository.findByKeyHash(OpaqueTokens.hash("uploads/b.png"))).willReturn(Optional.empty());

        listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png")));

        then(deletionAttempt).should().attempt("uploads/a.png", 2, DUE);
        then(deletionAttempt).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("하나가 예외여도 나머지는 지우고 예외를 전파하지 않는다 — 실행기 스레드를 죽이지 않는다")
    void bestEffortOnFailure() {
        S3Deletion b = row("uploads/b.png", 0);
        given(repository.findByKeyHash(OpaqueTokens.hash("uploads/a.png"))).willThrow(new RuntimeException("DB 장애"));
        given(repository.findByKeyHash(OpaqueTokens.hash("uploads/b.png"))).willReturn(Optional.of(b));
        willThrow(new RuntimeException("S3 장애")).given(deletionAttempt).attempt("uploads/b.png", 0, DUE);

        assertThatCode(() -> listener.on(new S3DeleteEvent(List.of("uploads/a.png", "uploads/b.png"))))
                .doesNotThrowAnyException();

        then(deletionAttempt).should().attempt("uploads/b.png", 0, DUE);
    }

    private static S3Deletion row(String key, int attempts) {
        S3Deletion row = mock(S3Deletion.class);
        given(row.getS3Key()).willReturn(key);
        given(row.getAttempts()).willReturn(attempts);
        given(row.getNextAttemptAt()).willReturn(DUE);
        return row;
    }
}
