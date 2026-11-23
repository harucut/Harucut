package com.harucut.auth.security;

import com.harucut.support.UserFixtures;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

class CustomUserPrincipalTest {

    private static final String EMAIL = "user@harucut.com";

    @Nested
    @DisplayName("권한 매핑")
    class Authorities {

        @Test
        @DisplayName("ACTIVE 사용자는 자기 역할을 권한으로 갖는다")
        void activeUserGetsItsRole() {
            assertThat(principal(UserStatus.ACTIVE, UserRole.ROLE_USER).getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");

            assertThat(principal(UserStatus.ACTIVE, UserRole.ROLE_ADMIN).getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("탈퇴 요청 사용자는 원래 역할을 잃고 ROLE_DELETED_REQUESTED만 갖는다")
        void deletionRequestedUserLosesItsRole() {
            assertThat(principal(UserStatus.DELETED_REQUESTED, UserRole.ROLE_USER).getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_DELETED_REQUESTED");
        }

        @Test
        @DisplayName("차단 사용자는 ROLE_BLOCKED 하나만 — 마이페이지와 탈퇴만 열린다. ADMIN 도 역할을 잃는다")
        void blockedUserHasOnlyBlockedRole() {
            assertThat(principal(UserStatus.BLOCKED, UserRole.ROLE_ADMIN).getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_BLOCKED");
        }

        @Test
        @DisplayName("삭제 사용자는 권한이 없다")
        void deletedUserHasNoAuthority() {
            assertThat(principal(UserStatus.DELETED, UserRole.ROLE_USER).getAuthorities()).isEmpty();
        }

        @Test
        @DisplayName("UserDetails의 username은 닉네임이 아니라 email이다")
        void exposesEmailAsUsername() {
            assertThat(principal(UserStatus.ACTIVE, UserRole.ROLE_USER).getUsername()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("차단된 사용자도 계정 상태 플래그는 전부 true다")
        void keepsAccountStatusFlagsTrue() {
            CustomUserPrincipal principal = principal(UserStatus.BLOCKED, UserRole.ROLE_USER);

            assertThat(principal.isEnabled()).isTrue();
            assertThat(principal.isAccountNonLocked()).isTrue();
            assertThat(principal.isAccountNonExpired()).isTrue();
            assertThat(principal.isCredentialsNonExpired()).isTrue();
        }
    }

    private static CustomUserPrincipal principal(UserStatus status, UserRole role) {
        return new CustomUserPrincipal(UserFixtures.localUser(EMAIL, "password", status, role));
    }
}