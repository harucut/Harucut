package com.harucut.workspace.repository;

import com.harucut.config.JpaAuditingConfig;
import com.harucut.support.FixedClockConfig;
import com.harucut.user.entity.User;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceMembership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureJson   // 다른 엔티티의 JSON 컨버터가 ObjectMapper 를 요구한다
@Import({JpaAuditingConfig.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("WorkspaceRepository")
class WorkspaceRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Test
    @DisplayName("사용자당 개인 공간은 하나다 — 두 번째는 유니크 위반")
    void onePersonalWorkspacePerUser() {
        User user = em.persist(User.localUser("me@harucut.com", "encoded", "나"));
        workspaceRepository.saveAndFlush(Workspace.personal(user.getId()));

        assertThatThrownBy(() -> workspaceRepository.saveAndFlush(Workspace.personal(user.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("탈퇴 정리: 멤버십과 개인 공간을 지운다")
    void deletesForUser() {
        User user = em.persist(User.localUser("me@harucut.com", "encoded", "나"));
        Workspace workspace = workspaceRepository.saveAndFlush(Workspace.personal(user.getId()));
        membershipRepository.saveAndFlush(WorkspaceMembership.owner(workspace.getId(), user.getId()));

        membershipRepository.deleteByUserId(user.getId());
        workspaceRepository.deletePersonalByOwner(user.getId());

        assertThat(workspaceRepository.findByPersonalOwnerUserId(user.getId())).isEmpty();
        assertThat(membershipRepository.findAll()).isEmpty();
    }
}
