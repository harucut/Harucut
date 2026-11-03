package com.harucut.frame.repository;

import com.harucut.config.JpaAuditingConfig;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.support.FixedClockConfig;
import com.harucut.user.entity.User;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 탈퇴 하드삭제용 벌크 쿼리 검증(FrameUserDeletionHandler 가 쓰는 셋). JPQL 벌크 DELETE는 cascade/orphanRemoval을 우회하므로
// 컴포넌트 → 프레임 순서가 강제된다 — 그 사실 자체를 FK 위반으로 증명한다
@DataJpaTest
@AutoConfigureJson
@Import({JpaAuditingConfig.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("프레임 탈퇴 삭제 쿼리")
class FrameDeletionQueryTest {

    private static final long PERSONAL_WORKSPACE_ID = -1L;   // 공간이 없던 옛 행(workspaceId null)만 쓴다

    @Autowired
    private TestEntityManager em;

    @Autowired
    private FrameRepository frameRepository;

    @Test
    @DisplayName("내 프레임과 컴포넌트만 지워지고 남의 것과 시스템 프레임은 남는다")
    void deletesOnlyMine() {
        User mine = em.persist(User.localUser("mine@harucut.com", "encoded", "나"));
        User other = em.persist(User.localUser("other@harucut.com", "encoded", "남"));
        em.persist(frameWithComponents(mine, 1));
        em.persist(frameWithComponents(other, 2));
        em.persist(systemFrame("시스템"));
        em.flush();
        em.clear();

        List<Long> frameIds = frameRepository.findPersonalWithComponents(mine.getId(), PERSONAL_WORKSPACE_ID)
                .stream().map(Frame::getId).toList();
        frameRepository.deleteComponentsByFrameIdIn(frameIds);
        frameRepository.deleteByIdIn(frameIds);

        assertThat(frameRepository.findAll())
                .extracting(Frame::getTitle)
                .containsExactlyInAnyOrder("프레임2", "시스템");
    }

    // cascade는 엔티티 단위 삭제의 기능이다 — 벌크 DELETE가 그걸 우회한다는 증거
    @Test
    @DisplayName("컴포넌트를 먼저 지우지 않으면 FK가 막는다")
    void fkBlocksFrameFirstDeletion() {
        User mine = em.persist(User.localUser("mine@harucut.com", "encoded", "나"));
        Frame frame = em.persist(frameWithComponents(mine, 1));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> frameRepository.deleteByIdIn(List.of(frame.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("삭제용 조회는 내 프레임만 컴포넌트까지 초기화해서 가져온다")
    void fetchJoinLoadsMineWithComponents() {
        User mine = em.persist(User.localUser("mine@harucut.com", "encoded", "나"));
        User other = em.persist(User.localUser("other@harucut.com", "encoded", "남"));
        em.persist(frameWithComponents(mine, 1));
        em.persist(frameWithComponents(other, 2));
        em.flush();
        em.clear();

        assertThat(frameRepository.findPersonalWithComponents(mine.getId(), PERSONAL_WORKSPACE_ID))
                .singleElement()
                .satisfies(frame -> {
                    assertThat(Hibernate.isInitialized(frame.getComponents())).isTrue();
                    assertThat(frame.getComponents()).hasSize(2);
                });
    }

    // ── fixtures (FrameListQueryCountTest와 동일) ──────────────────────────────

    private static Frame frameWithComponents(User user, int index) {
        Frame frame = Frame.owned(user, "프레임" + index, "설명", "uploads/p" + index + ".png",
                FrameType.CLASSIC, new BackgroundAttributes.Color("#FFF"));
        addTwoComponents(frame);
        return frame;
    }

    private static Frame systemFrame(String title) {
        Frame frame = Frame.system(title, "설명", "uploads/s.png",
                FrameType.CLASSIC, new BackgroundAttributes.Color("#FFF"));
        addTwoComponents(frame);
        return frame;
    }

    private static void addTwoComponents(Frame frame) {
        for (int i = 1; i <= 2; i++) {
            frame.addComponent(FrameComponent.builder()
                    .source("uploads/c" + i + ".png").type(ComponentType.PHOTO).zIndex(i).build());
        }
    }
}
