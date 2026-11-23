package com.harucut.media.entity;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ComposeJob")
class ComposeJobTest {

    private static final List<String> FOUR_KEYS = List.of(
            "uploads/users/abc/fourcuts/sources/1.jpg",
            "uploads/users/abc/fourcuts/sources/2.jpg",
            "uploads/users/abc/fourcuts/sources/3.jpg",
            "uploads/users/abc/fourcuts/sources/4.jpg");

    @Nested
    @DisplayName("create — 팩토리 불변식")
    class Create {

        @Test
        @DisplayName("생성 직후 상태는 PENDING이다")
        void startsPending() {
            ComposeJob job = job();

            assertThat(job.getStatus()).isEqualTo(ComposeStatus.PENDING);
        }

        @Test
        @DisplayName("소유자가 없으면 거부한다")
        void rejectsNullUser() {
            assertThatThrownBy(() -> ComposeJob.create(null, 1L, "idem-key", FOUR_KEYS, spec()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("원본이 3장이면 거부한다 — 네컷은 정확히 4장이다")
        void rejectsThreeKeys() {
            assertThatThrownBy(() -> ComposeJob.create(user(), 1L, "idem-key",
                    FOUR_KEYS.subList(0, 3), spec()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("원본에 빈 key가 섞여 있으면 거부한다")
        void rejectsBlankKey() {
            assertThatThrownBy(() -> ComposeJob.create(user(), 1L, "idem-key",
                    List.of("uploads/a.jpg", " ", "uploads/c.jpg", "uploads/d.jpg"), spec()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sourceKeys()가 슬롯 순서 그대로 4개를 돌려준다")
        void sourceKeysInOrder() {
            assertThat(job().sourceKeys()).containsExactlyElementsOf(FOUR_KEYS);
        }
    }

    // 상태 전이 자체는 ComposeJobRepository 의 조건부 UPDATE 다 — ComposeJobRepositoryTest 에서 본다
    @Nested
    @DisplayName("실패 사유 자르기")
    class FailureReason {

        @Test
        @DisplayName("긴 실패 사유는 255자로 잘린다 — 컬럼 길이 초과로 죽지 않는다")
        void longReasonTruncated() {
            assertThat(ComposeJob.truncateFailureReason("x".repeat(300))).hasSize(255);
        }

        @Test
        @DisplayName("짧은 사유와 null 은 그대로다")
        void shortReasonKept() {
            assertThat(ComposeJob.truncateFailureReason("원본을 읽을 수 없다")).isEqualTo("원본을 읽을 수 없다");
            assertThat(ComposeJob.truncateFailureReason(null)).isNull();
        }
    }

    // ── fixtures ──────────────────────────────

    private static ComposeJob job() {
        return ComposeJob.create(user(), 1L, "idem-key", FOUR_KEYS, spec());
    }

    private static User user() {
        return User.localUser("owner@harucut.com", "encoded", "하루컷");
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000,
                new BackgroundAttributes.Color("#FFE4E1"),
                FrameType.CLASSIC.getLayout().slots(),
                List.of(false, false, false, false),
                List.of());
    }
}
