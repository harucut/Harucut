package com.harucut.frame.repository;

import com.harucut.config.JpaAuditingConfig;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.entity.FrameComponent;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.support.FixedClockConfig;
import com.harucut.user.entity.User;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// N+1 부재의 자물쇠: 목록 조회가 프레임·컴포넌트 개수와 무관하게 SELECT 1번임을
// Hibernate 통계로 센다. fetch join을 지우면 이 테스트가 먼저 깨진다 (완료 체크 항목)
@DataJpaTest
@AutoConfigureJson
@Import({JpaAuditingConfig.class, FixedClockConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("프레임 목록 쿼리 수")
class FrameListQueryCountTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private FrameRepository frameRepository;

    @Test
    @DisplayName("사용자 목록은 프레임 3개 × 컴포넌트 2개를 전부 읽어도 SELECT 1번이다")
    void userListIsSingleQuery() {
        User user = em.persist(User.localUser("owner@harucut.com", "encoded", "소유자"));
        for (int i = 1; i <= 3; i++) {
            em.persist(frameWithComponents(user, i));
        }
        em.flush();
        em.clear();
        Statistics statistics = statistics();
        statistics.clear();

        List<Frame> frames = frameRepository.findPersonalWithComponentsByUser(user);
        // 지연 로딩이었다면 여기서 프레임마다 추가 SELECT가 나간다
        frames.forEach(frame -> assertThat(frame.getComponents()).hasSize(2));

        assertThat(frames).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("시스템 목록도 SELECT 1번이고 최신순이다")
    void systemListIsSingleQueryNewestFirst() {
        Frame older = em.persist(systemFrame("먼저 만든 것"));
        Frame newer = em.persist(systemFrame("나중 만든 것"));
        em.flush();
        // FixedClock이라 auditing이 둘 다 같은 createdAt을 찍는다 — 정렬 검증을 위해 직접 벌린다
        em.getEntityManager()
                .createNativeQuery("update frame set created_at = ? where frame_id = ?")
                .setParameter(1, FixedClockConfig.FIXED_NOW.minusDays(7))
                .setParameter(2, older.getId())
                .executeUpdate();
        em.clear();
        Statistics statistics = statistics();
        statistics.clear();

        List<Frame> frames = frameRepository.findAllWithComponentsBySystem();
        frames.forEach(frame -> assertThat(frame.getComponents()).hasSize(2));

        assertThat(frames).extracting(Frame::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private Statistics statistics() {
        return em.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
    }

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
