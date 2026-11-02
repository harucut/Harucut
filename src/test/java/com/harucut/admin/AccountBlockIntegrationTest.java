package com.harucut.admin;

import com.harucut.audit.repository.AuditLogRepository;
import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.auth.service.RefreshTokenRepository;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * 계정 정지(A17, R5 L399·L636) — 실제 보안 체인·인터셉터로. 토큰은 정지 전에 발급한 그대로 쓴다: 관리·사진 경로만 바로 막히고,
 * 일반 API 는 access 만료까지 남는다. Redis 는 테스트에 없어 세션 저장소만 목으로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("계정 정지 — 통합")
class AccountBlockIntegrationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private RefreshTokenRepository refreshTokenRepository;

    private User admin;
    private User member;
    private Cookie adminCookie;
    private Cookie memberCookie;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.localUser("admin@harucut.com", "e", "관리자"));
        member = userRepository.save(User.localUser("member@harucut.com", "e", "회원"));
        entityManager.flush();
        jdbcTemplate.update("update users set user_role = 'ROLE_ADMIN' where user_id = ?", admin.getId());
        entityManager.clear();
        adminCookie = cookie(admin.getPublicId(), UserRole.ROLE_ADMIN);
        memberCookie = cookie(member.getPublicId(), UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("이메일로 찾기 — 정확히 일치하는 계정 배열, 없으면 빈 배열, 일반 사용자는 403")
    void find() {
        assertThat(mvc.get().uri("/api/admin/users?email={e}", "member@harucut.com").cookie(adminCookie)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.data[0].userId").isEqualTo(member.getPublicId());
                    assertThat(json).extractingPath("$.data[0].email").isEqualTo("member@harucut.com");
                    assertThat(json).extractingPath("$.data[0].username").isEqualTo("회원");
                    assertThat(json).extractingPath("$.data[0].provider").isEqualTo("HARUCUT");
                    assertThat(json).extractingPath("$.data[0].userRole").isEqualTo("ROLE_USER");
                    assertThat(json).extractingPath("$.data[0].userStatus").isEqualTo("ACTIVE");
                });
        assertThat(mvc.get().uri("/api/admin/users?email={e}", "member@harucut").cookie(adminCookie)).hasStatusOk().bodyJson()
                .extractingPath("$.data").asList().isEmpty();
        assertThat(mvc.get().uri("/api/admin/users?email={e}", "member@harucut.com").cookie(memberCookie))
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");
    }

    @Test
    @DisplayName("정지 — 모든 세션을 끊고 감사에 사유를 남긴다. 다시 해도 그대로, 해제하면 ACTIVE")
    void blockAndUnblock() {
        assertThat(block(member.getPublicId(), "약관 위반", adminCookie)).hasStatusOk().bodyJson().satisfies(json -> {
            assertThat(json).extractingPath("$.data.userId").isEqualTo(member.getPublicId());
            assertThat(json).extractingPath("$.data.userStatus").isEqualTo("BLOCKED");
        });
        then(refreshTokenRepository).should().deleteAll(member.getPublicId());
        assertThat(actions("USER_BLOCKED")).isEqualTo(1);

        assertThat(block(member.getPublicId(), "다시", adminCookie)).hasStatusOk();
        assertThat(actions("USER_BLOCKED")).isEqualTo(1);

        assertThat(post("/api/admin/users/" + member.getPublicId() + "/unblock", "소명 확인", adminCookie)).hasStatusOk().bodyJson()
                .extractingPath("$.data.userStatus").isEqualTo("ACTIVE");
        assertThat(actions("USER_UNBLOCKED")).isEqualTo(1);
        assertThat(post("/api/admin/users/" + member.getPublicId() + "/unblock", "다시", adminCookie)).hasStatusOk().bodyJson()
                .extractingPath("$.data.userStatus").isEqualTo("ACTIVE");
        assertThat(actions("USER_UNBLOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("막는 경우: 자기 자신 403, 없는 계정 404, 탈퇴 요청 중 AUTH-005, 사유 없음 GEN-003, 일반 사용자 403")
    void refusals() {
        assertThat(block(admin.getPublicId(), "실수", adminCookie)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");
        assertThat(block("no-such-user", "x", adminCookie)).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("GEN-031");
        assertThat(block(member.getPublicId(), " ", adminCookie)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("GEN-003");
        assertThat(block(admin.getPublicId(), "x", memberCookie)).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");

        jdbcTemplate.update("update users set user_status = 'DELETED_REQUESTED' where user_id = ?", member.getId());
        entityManager.clear();
        assertThat(block(member.getPublicId(), "x", adminCookie)).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("AUTH-005");

        then(refreshTokenRepository).should(never()).deleteAll(member.getPublicId());
        assertThat(actions("USER_BLOCKED")).isZero();
    }

    @Test
    @DisplayName("정지 전에 받은 access 토큰 — 관리·사진·행사 경로는 바로 403 GEN-021, 일반 API 는 만료까지 남는다")
    void blockedTokenStillAlive() {
        assertThat(mvc.get().uri("/api/auth/me/events").cookie(memberCookie)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/workspaces").cookie(memberCookie)).hasStatusOk();

        assertThat(block(member.getPublicId(), "약관 위반", adminCookie)).hasStatusOk();
        entityManager.flush();

        assertForbidden(mvc.get().uri("/api/auth/me/events").cookie(memberCookie).exchange());
        assertForbidden(mvc.get().uri("/api/auth/workspaces").cookie(memberCookie).exchange());
        assertForbidden(mvc.get().uri("/api/auth/events/anything").cookie(memberCookie).exchange());
        assertForbidden(mvc.get().uri("/api/auth/events/anything/photos").cookie(memberCookie).exchange());
        assertThat(mvc.get().uri("/api/auth/status").cookie(memberCookie)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/terms/consents/me").cookie(memberCookie)).hasStatusOk();
    }

    @Test
    @DisplayName("ADMIN 권한이 빠지거나 정지된 관리자 — access 토큰이 ADMIN 이어도 관리자 경로는 바로 403 GEN-021")
    void demotedAdminTokenStillAlive() {
        assertThat(mvc.get().uri("/api/admin/users?email=a@b.c").cookie(adminCookie)).hasStatusOk();

        jdbcTemplate.update("update users set user_role = 'ROLE_USER' where user_id = ?", admin.getId());
        assertForbidden(mvc.get().uri("/api/admin/users?email=a@b.c").cookie(adminCookie).exchange());
        assertForbidden(mvc.post().uri("/api/admin/users/{id}/block", member.getPublicId()).cookie(adminCookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}").exchange());

        jdbcTemplate.update("update users set user_role = 'ROLE_ADMIN', user_status = 'BLOCKED' where user_id = ?", admin.getId());
        assertForbidden(mvc.get().uri("/api/admin/users?email=a@b.c").cookie(adminCookie).exchange());
    }

    private MvcTestResult block(String publicId, String reason, Cookie cookie) {
        return post("/api/admin/users/" + publicId + "/block", reason, cookie);
    }

    private MvcTestResult post(String uri, String reason, Cookie cookie) {
        MvcTestResult result = mvc.post().uri(uri).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}").exchange();
        entityManager.flush();   // 운영에서는 요청마다 커밋된다 — 다음 요청의 조회와 같은 상태로
        entityManager.clear();
        return result;
    }

    private void assertForbidden(MvcTestResult result) {
        assertThat(result).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");
    }

    private long actions(String action) {
        return auditLogRepository.findAll().stream().filter(log -> log.getAction().equals(action)
                && log.getActorUserId().equals(admin.getId()) && log.getTargetId().equals(member.getPublicId())).count();
    }

    private Cookie cookie(String publicId, UserRole role) {
        return new Cookie(CookieManager.ACCESS_TOKEN, jwtTokenService.createAccessToken(publicId, role, UserStatus.ACTIVE).value());
    }
}
