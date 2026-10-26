package com.harucut.storage.service;

import com.harucut.common.utils.OpaqueTokens;
import com.harucut.storage.repository.S3DeletionRepository;
import com.harucut.storage.event.S3DeleteEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

// FrameAssetManagerTest에서 이사 온 테스트 — 거르기·발행 로직이 storage 공용 부품이 되면서 함께 왔다
@ExtendWith(MockitoExtension.class)
@DisplayName("S3Deleter")
class S3DeleterTest {

    private static final String KEY = "uploads/users/AbCdEf12Gh/fourcuts/photo1.png";
    private static final String EXTERNAL_URL = "https://cdn.example.com/stickers/heart.png";

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private S3DeletionRepository deletionRepository;

    private S3Deleter s3Deleter;

    @BeforeEach
    void setUp() {
        s3Deleter = new S3Deleter(eventPublisher, deletionRepository, Clock.systemUTC());
    }

    @Test
    @DisplayName("null·빈 값·외부 URL을 거르고 중복을 제거해 이벤트로 발행한다")
    void filtersAndDeduplicates() {
        s3Deleter.deleteAfterCommit(Arrays.asList(KEY, null, "  ", EXTERNAL_URL, KEY));

        ArgumentCaptor<S3DeleteEvent> captor = ArgumentCaptor.forClass(S3DeleteEvent.class);
        then(eventPublisher).should().publishEvent(captor.capture());
        assertThat(captor.getValue().keys()).containsExactly(KEY);
        // 같은 트랜잭션에 삭제 예약 행 — key 하나에 한 번
        then(deletionRepository).should().schedule(eq(KEY), eq(OpaqueTokens.hash(KEY)), any(), any());
        then(deletionRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("지울 관리 key가 하나도 없으면 이벤트를 발행하지 않는다")
    void nothingManagedNoPublish() {
        s3Deleter.deleteAfterCommit(Arrays.asList(null, "  ", EXTERNAL_URL));

        then(eventPublisher).shouldHaveNoInteractions();
        then(deletionRepository).shouldHaveNoInteractions();
    }
}
