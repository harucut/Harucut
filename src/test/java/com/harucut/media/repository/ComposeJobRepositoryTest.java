package com.harucut.media.repository;

import com.harucut.config.JpaAuditingConfig;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.converter.ComposeSpecConverter;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.support.FixedClockConfig;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureJson
@Import({JpaAuditingConfig.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("ComposeJobRepository")
class ComposeJobRepositoryTest {

    private static final List<String> FOUR_KEYS = List.of(
            "uploads/users/abc/fourcuts/sources/1.jpg",
            "uploads/users/abc/fourcuts/sources/2.jpg",
            "uploads/users/abc/fourcuts/sources/3.jpg",
            "uploads/users/abc/fourcuts/sources/4.jpg");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ComposeJobRepository composeJobRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("저장 후 재조회하면 스펙 스냅샷까지 그대로 돌아온다 — 왕복 무손실")
    void roundTrip() {
        User user = persistUser("owner@harucut.com");
        ComposeJob saved = em.persist(ComposeJob.create(user, 7L, "idem-1", FOUR_KEYS, spec()));
        em.flush();
        em.clear();

        ComposeJob found = composeJobRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ComposeStatus.PENDING);
        assertThat(found.getFrameId()).isEqualTo(7L);
        assertThat(found.sourceKeys()).containsExactlyElementsOf(FOUR_KEYS);
        assertThat(found.getSpec()).isEqualTo(spec());
        assertThat(found.getCreatedAt()).isEqualTo(FixedClockConfig.FIXED_NOW);
    }

    @Nested
    @DisplayName("멱등 unique — (user, idempotencyKey)")
    class IdempotencyUnique {

        @Test
        @DisplayName("같은 사용자가 같은 key로 두 번 저장하면 DB가 거부한다")
        void duplicateRejected() {
            User user = persistUser("owner@harucut.com");
            em.persist(ComposeJob.create(user, 7L, "same-key", FOUR_KEYS, spec()));
            em.flush();

            assertThatThrownBy(() -> {
                composeJobRepository.save(ComposeJob.create(user, 7L, "same-key", FOUR_KEYS, spec()));
                composeJobRepository.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("다른 사용자면 같은 key라도 저장된다 — 멱등의 범위는 사용자 단위다")
        void sameKeyDifferentUserAllowed() {
            User first = persistUser("first@harucut.com");
            User second = persistUser("second@harucut.com");
            em.persist(ComposeJob.create(first, 7L, "same-key", FOUR_KEYS, spec()));
            em.flush();

            ComposeJob saved = composeJobRepository
                    .save(ComposeJob.create(second, 7L, "same-key", FOUR_KEYS, spec()));
            composeJobRepository.flush();

            assertThat(saved.getId()).isNotNull();
        }

        @Test
        @DisplayName("멱등 조회는 주체(scope)+key 로 — 개인 작업의 scope 는 U:{userId}")
        void findByIdempotencyKey() {
            User user = persistUser("owner@harucut.com");
            ComposeJob job = em.persist(ComposeJob.create(user, 7L, "retry-key", FOUR_KEYS, spec()));
            em.flush();
            em.clear();

            assertThat(job.getIdempotencyScope()).isEqualTo("U:" + user.getId());
            assertThat(job.getStorageRoot()).isEqualTo("uploads/users/" + user.getPublicId() + "/");
            assertThat(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:" + user.getId(), "retry-key"))
                    .map(ComposeJob::getId).hasValue(job.getId());
            assertThat(composeJobRepository.findByIdempotencyScopeAndIdempotencyKey("U:" + user.getId(), "unknown-key"))
                    .isEmpty();
        }

        @Test
        @DisplayName("다른 주체는 같은 key 를 써도 독립 작업이다")
        void sameKeyDifferentScopes() {
            User first = persistUser("a@harucut.com");
            User second = persistUser("b@harucut.com");
            em.persist(ComposeJob.create(first, 7L, "same-key", FOUR_KEYS, spec()));
            em.persist(ComposeJob.create(second, 7L, "same-key", FOUR_KEYS, spec()));

            org.assertj.core.api.Assertions.assertThatCode(() -> em.flush()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("findByIdAndUser")
    class FindByIdAndUser {

        @Test
        @DisplayName("내 작업이면 찾는다")
        void mine() {
            User user = persistUser("owner@harucut.com");
            ComposeJob job = em.persist(ComposeJob.create(user, 7L, "idem-1", FOUR_KEYS, spec()));
            em.flush();
            em.clear();

            assertThat(composeJobRepository.findByIdAndUser(job.getId(), user)).isPresent();
        }

        @Test
        @DisplayName("남의 작업은 빈 결과다 — 없는 것과 구분되지 않는다")
        void others() {
            User owner = persistUser("owner@harucut.com");
            User stranger = persistUser("stranger@harucut.com");
            ComposeJob job = em.persist(ComposeJob.create(owner, 7L, "idem-1", FOUR_KEYS, spec()));
            em.flush();
            em.clear();

            assertThat(composeJobRepository.findByIdAndUser(job.getId(), stranger)).isEmpty();
        }

        @Test
        @DisplayName("없는 id도 빈 결과다")
        void missing() {
            User user = persistUser("owner@harucut.com");
            em.flush();

            assertThat(composeJobRepository.findByIdAndUser(999L, user)).isEmpty();
        }
    }

    @Nested
    @DisplayName("깨진 스펙 JSON")
    class CorruptSpec {

        @Test
        @DisplayName("스펙 JSON이 깨져 있으면 크게 실패한다 — 깨진 스펙으로 그리는 척하지 않는다")
        void corruptSpecFailsLoud() {
            ComposeSpecConverter converter = new ComposeSpecConverter(objectMapper);

            assertThatThrownBy(() -> converter.convertToEntityAttribute("{broken"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("탈퇴 삭제 쿼리")
    class DeletionQueries {

        @Test
        @DisplayName("결과 키 조회는 내 완료 작업 것만 나온다 — 미완료(null)는 빠진다")
        void resultKeyProjection() {
            User mine = persistUser("mine@harucut.com");
            User other = persistUser("other@harucut.com");
            ComposeJob done = em.persist(ComposeJob.create(mine, 7L, "idem-1", FOUR_KEYS, spec()));
            em.persist(ComposeJob.create(mine, 7L, "idem-2", FOUR_KEYS, spec()));
            ComposeJob othersDone = em.persist(ComposeJob.create(other, 7L, "idem-1", FOUR_KEYS, spec()));
            em.flush();
            markDone(done.getId(), "uploads/results/r1.png");
            markDone(othersDone.getId(), "uploads/results/r2.png");

            assertThat(composeJobRepository.findResultKeysByUserId(mine.getId()))
                    .containsExactly("uploads/results/r1.png");
        }

        @Test
        @DisplayName("벌크 삭제는 내 작업만 지운다")
        void bulkDeleteMineOnly() {
            User mine = persistUser("mine@harucut.com");
            User other = persistUser("other@harucut.com");
            em.persist(ComposeJob.create(mine, 7L, "idem-1", FOUR_KEYS, spec()));
            em.persist(ComposeJob.create(other, 7L, "idem-1", FOUR_KEYS, spec()));
            em.flush();

            composeJobRepository.deleteByUserId(mine.getId());

            assertThat(composeJobRepository.findAll()).hasSize(1);
        }
    }

    // 이 두 쿼리가 "합성이 정확히 한 번 실행된다"의 전부다.
    // 지금까지 어느 테스트에서도 실행되지 않았고, 그래서 즉시 접수가 죽은 것도 못 잡았다.

    @Nested
    @DisplayName("claim — 조건부 UPDATE 선점")
    class Claim {

        private static final Duration STALE_AFTER = Duration.ofMinutes(10);

        @Test
        @DisplayName("갓 만든 PENDING(started_at IS NULL)은 선점된다")
        void claimsFreshJob() {
            Long jobId = persistJob("idem-fresh");

            assertThat(claim(jobId)).isEqualTo(1);
            assertThat(reload(jobId).getStartedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("이미 선점된 Job은 유예 시간 안에서 두 번 선점되지 않는다")
        void rejectsSecondClaimWithinWindow() {
            Long jobId = persistJob("idem-twice");
            claim(jobId);
            em.clear();

            assertThat(claim(jobId))
                    .as("두 인스턴스가 동시에 쳐도 한 쪽만 1을 받아야 한다")
                    .isZero();
        }

        @Test
        @DisplayName("유예 시간이 지난 선점은 회수된다 — 선점한 인스턴스가 죽어도 복구된다")
        void reclaimsAfterStaleWindow() {
            Long jobId = persistJob("idem-stale");
            startedAt(jobId, NOW.minusMinutes(30));

            assertThat(claim(jobId)).isEqualTo(1);
            assertThat(reload(jobId).getStartedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("PENDING이 아닌 Job은 선점되지 않는다 — 끝난 작업을 다시 돌리지 않는다")
        void skipsFinishedJob() {
            Long jobId = persistJob("idem-done");
            markDone(jobId, "uploads/r.png");

            assertThat(claim(jobId)).isZero();
        }

        private int claim(Long jobId) {
            int affected = composeJobRepository.claim(jobId, NOW, NOW.minus(STALE_AFTER));
            em.flush();
            return affected;
        }
    }

    @Nested
    @DisplayName("findStalled — 재실행 대상 조회")
    class FindStalled {

        private final Pageable firstTwenty = PageRequest.of(0, 20);

        @Test
        @DisplayName("started_at이 NULL이면 시간 조건 없이 바로 잡힌다")
        void picksUpNeverStartedJob() {
            Long jobId = persistJob("idem-null");

            assertThat(stalled()).extracting(ComposeJob::getId).containsExactly(jobId);
        }

        @Test
        @DisplayName("갓 선점된 Job은 잡히지 않는다 — 아직 도는 중이다")
        void skipsRecentlyClaimedJob() {
            Long jobId = persistJob("idem-recent");
            startedAt(jobId, NOW.minusSeconds(5));

            assertThat(stalled()).isEmpty();
        }

        @Test
        @DisplayName("오래 멈춰 있는 Job은 잡힌다")
        void picksUpStalledJob() {
            Long jobId = persistJob("idem-old");
            startedAt(jobId, NOW.minusMinutes(30));

            assertThat(stalled()).extracting(ComposeJob::getId).containsExactly(jobId);
        }

        @Test
        @DisplayName("DONE·FAILED는 아무리 오래돼도 잡히지 않는다 — 재실행의 유일한 안전장치다")
        void neverPicksUpFinishedJobs() {
            Long done = persistJob("idem-fin-done");
            Long failed = persistJob("idem-fin-failed");
            startedAt(done, NOW.minusHours(5));
            startedAt(failed, NOW.minusHours(5));
            markDone(done, "uploads/r.png");
            markFailed(failed, "터졌다");

            assertThat(stalled()).isEmpty();
        }

        @Test
        @DisplayName("User 가 없는 작업(행사 참가자)도 재실행 대상에 잡힌다 — inner join 이면 조용히 빠진다")
        void picksUpJobWithoutUser() {
            Long jobId = persistJob("idem-no-user");
            em.getEntityManager().createNativeQuery("UPDATE compose_job SET user_id = NULL WHERE compose_job_id = ?")
                    .setParameter(1, jobId).executeUpdate();
            em.clear();

            assertThat(stalled()).extracting(ComposeJob::getId).containsExactly(jobId);
        }

        @Test
        @DisplayName("user를 함께 가져온다 — 워커가 트랜잭션 밖에서 publicId를 읽는다")
        void fetchesUserEagerly() {
            persistJob("idem-fetch");

            ComposeJob job = stalled().get(0);
            em.clear();   // 영속성 컨텍스트를 비워도 이미 로딩됐으면 읽힌다

            assertThat(job.getUser().getPublicId()).isNotBlank();
        }

        @Test
        @DisplayName("id 오름차순으로 나온다 — 오래 기다린 Job이 먼저다")
        void ordersByIdAscending() {
            Long first = persistJob("idem-a");
            Long second = persistJob("idem-b");
            Long third = persistJob("idem-c");

            assertThat(stalled()).extracting(ComposeJob::getId)
                    .containsExactly(first, second, third);
        }

        @Test
        @DisplayName("limit을 넘겨 받지 않는다 — 한 주기에 밀어 넣는 양의 상한이다")
        void respectsLimit() {
            persistJob("idem-1");
            persistJob("idem-2");
            persistJob("idem-3");

            assertThat(composeJobRepository.findStalled(STALE_BEFORE, PageRequest.of(0, 2)))
                    .hasSize(2);
        }

        private List<ComposeJob> stalled() {
            return composeJobRepository.findStalled(STALE_BEFORE, firstTwenty);
        }
    }

    /*
     * 완료·실패 전이는 조건부 UPDATE 다. 영향 행 수 1 = 이 호출이 이겼다, 0 = 이미 다른 쪽이 끝냈다.
     * 서비스는 이 값으로 미디어 생성·원본 삭제를 한 번만 한다.
     */
    @Nested
    @DisplayName("완료·실패 전이 — 조건부 UPDATE")
    class Transitions {

        @Test
        @DisplayName("PENDING 을 DONE 으로 바꾸면 1 이고 결과 key 가 남는다")
        void markDoneFromPending() {
            Long jobId = persistJob("idem-t-done");

            assertThat(markDone(jobId, "uploads/r.png")).isEqualTo(1);
            assertThat(reload(jobId).getStatus()).isEqualTo(ComposeStatus.DONE);
            assertThat(reload(jobId).getResultKey()).isEqualTo("uploads/r.png");
        }

        @Test
        @DisplayName("두 번째 완료는 0 이다 — 재배달된 통지가 결과를 덮지 않는다")
        void secondMarkDoneLoses() {
            Long jobId = persistJob("idem-t-twice");
            markDone(jobId, "uploads/first.png");

            assertThat(markDone(jobId, "uploads/second.png")).isZero();
            assertThat(reload(jobId).getResultKey()).isEqualTo("uploads/first.png");
        }

        @Test
        @DisplayName("DONE 뒤의 실패는 0 이다 — 늦은 실패가 성공을 지우지 않는다")
        void failAfterDoneLoses() {
            Long jobId = persistJob("idem-t-late-fail");
            markDone(jobId, "uploads/r.png");

            assertThat(markFailed(jobId, "뒤늦은 실패")).isZero();
            assertThat(reload(jobId).getStatus()).isEqualTo(ComposeStatus.DONE);
            assertThat(reload(jobId).getFailureReason()).isNull();
        }

        @Test
        @DisplayName("실패 종류도 같은 조건부 UPDATE 로 남는다(V26)")
        void failureCodeStored() {
            Long jobId = persistJob("idem-failure-code");
            assertThat(composeJobRepository.markFailed(jobId, "원본 없음", ComposeFailureCode.SOURCE_MISSING,
                    ComposeStatus.PENDING, ComposeStatus.FAILED)).isEqualTo(1);
            em.clear();
            assertThat(composeJobRepository.findById(jobId).orElseThrow().getFailureCode()).isEqualTo(ComposeFailureCode.SOURCE_MISSING);
        }

        @Test
        @DisplayName("FAILED 뒤의 완료는 0 이다")
        void doneAfterFailLoses() {
            Long jobId = persistJob("idem-t-late-done");
            markFailed(jobId, "원본 유실");

            assertThat(markDone(jobId, "uploads/late.png")).isZero();
            assertThat(reload(jobId).getStatus()).isEqualTo(ComposeStatus.FAILED);
            assertThat(reload(jobId).getResultKey()).isNull();
        }

        @Test
        @DisplayName("mediaId 는 따로 붙는다")
        void attachMedia() {
            Long jobId = persistJob("idem-t-media");
            markDone(jobId, "uploads/r.png");

            composeJobRepository.attachMedia(jobId, 42L);
            assertThat(reload(jobId).getMediaId()).isEqualTo(42L);
        }
    }

    // ── fixtures ──────────────────────────────

    private static final LocalDateTime NOW = FixedClockConfig.FIXED_NOW;
    private static final LocalDateTime STALE_BEFORE = NOW.minusMinutes(10);

    private Long persistJob(String idempotencyKey) {
        ComposeJob job = em.persist(ComposeJob.create(
                persistUser(idempotencyKey + "@harucut.com"), 7L, idempotencyKey, FOUR_KEYS, spec()));
        em.flush();
        return job.getId();
    }

    // started_at 은 엔티티에 setter 가 없다(선점 쿼리만 쓴다) — 테스트에서는 직접 갱신한다
    private void startedAt(Long jobId, LocalDateTime value) {
        em.getEntityManager()
                .createQuery("UPDATE ComposeJob j SET j.startedAt = :v WHERE j.id = :id")
                .setParameter("v", value).setParameter("id", jobId)
                .executeUpdate();
        em.clear();
    }

    private ComposeJob reload(Long jobId) {
        em.clear();
        return composeJobRepository.findById(jobId).orElseThrow();
    }

    private int markDone(Long jobId, String resultKey) {
        int affected = composeJobRepository.markDone(jobId, resultKey,
                ComposeStatus.PENDING, ComposeStatus.DONE);
        em.clear();
        return affected;
    }

    private int markFailed(Long jobId, String reason) {
        int affected = composeJobRepository.markFailed(jobId, reason,
                ComposeStatus.PENDING, ComposeStatus.FAILED);
        em.clear();
        return affected;
    }

    // ── fixtures ──────────────────────────────

    private User persistUser(String email) {
        return em.persist(User.localUser(email, "encoded", "닉네임"));
    }

    private static ComposeSpec spec() {
        return new ComposeSpec(2000, 6000,
                new BackgroundAttributes.Color("#FFE4E1"),
                FrameType.CLASSIC.getLayout().slots(),
                List.of(true, false, false, false),
                List.of(new ComposeSpec.Layer(
                        "uploads/users/abc/components/sticker.png",
                        120.5, 220.0, 360.0, 480.0, 1.0, 45.0, 3, 1.0)));
    }
}
