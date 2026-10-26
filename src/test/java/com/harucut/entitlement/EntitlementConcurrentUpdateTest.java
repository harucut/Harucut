package com.harucut.entitlement;

import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 이용권을 엔티티로 고쳐 저장할 때(회수) 그사이 바뀐 used·reserved 를 되돌리지 않는지 —
 * 다른 트랜잭션의 조건부 UPDATE 를 JDBC 직접 UPDATE 로 흉내 낸다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("이용권 — 엔티티 저장과 사용량 UPDATE 의 경합")
class EntitlementConcurrentUpdateTest {

    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("회수 저장은 바뀐 열(status·revoked_at·revoke_reason)만 쓴다 — 그사이 늘어난 reserved 가 남는다")
    void revokeKeepsConcurrentUsage() {
        User owner = userRepository.save(User.localUser("o@harucut.com", "e", "o"));
        WorkspaceResponse org = workspaceService.createOrganization(owner.getPublicId(), "공간");
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        Entitlement entitlement = entitlementRepository.save(Entitlement.manual(workspaceId, null,
                new Contract.Terms(10, null, null, null, Set.of()), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null, "t"));
        entityManager.flush();

        // 엔티티를 읽어 둔 뒤(reserved=0) 다른 쪽이 예약을 커밋했다
        Entitlement loaded = entitlementRepository.findById(entitlement.getId()).orElseThrow();
        jdbcTemplate.update("update entitlement set reserved = 3, used = 2 where entitlement_id = ?", entitlement.getId());

        loaded.revoke(LocalDateTime.now(), "회수");
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("select reserved from entitlement where entitlement_id = ?", Integer.class,
                entitlement.getId())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select used from entitlement where entitlement_id = ?", Integer.class,
                entitlement.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select status from entitlement where entitlement_id = ?", String.class,
                entitlement.getId())).isEqualTo("REVOKED");
    }
}
