package com.harucut.auth.oauth2;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomOAuth2User")
class CustomOAuth2UserTest {

    private static final Map<String, Object> ATTRIBUTES = Map.of("sub", "provider-sub-1");

    @Test
    @DisplayName("getName()은 제공자 식별자가 아니라 publicId다")
    void nameIsPublicId() {
        assertThat(principal(UserStatus.ACTIVE).getName()).isEqualTo("public-id-1");
    }

    /*
     * 위임체에 일부러 다른 권한을 심어둔다.
     * 실수로 delegate.getAuthorities() 를 흘려보내면 ROLE_DELEGATE_ONLY 가 나와 실패한다.
     */
    @Test
    @DisplayName("권한은 위임체가 아니라 AuthenticatedUser에서 온다")
    void authoritiesComeFromOurUser() {
        assertThat(principal(UserStatus.ACTIVE).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("BLOCKED 사용자는 ROLE_BLOCKED 하나만 갖는다")
    void blockedHasOnlyBlockedRole() {
        assertThat(principal(UserStatus.BLOCKED).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_BLOCKED");
    }

    @Test
    @DisplayName("attributes는 위임체 것을 그대로 넘긴다")
    void attributesAreDelegated() {
        assertThat(principal(UserStatus.ACTIVE).getAttributes()).isEqualTo(ATTRIBUTES);
    }

    /*
     * 네이버는 OAuth2라 ID 토큰이 없다. 한 클래스로 뭉쳐 OidcUser 를 구현하면
     * getIdToken() 이 null 인 채로 "OIDC 사용자"라고 말하게 된다. 그 상태로 돌아가지 못하게 막는다.
     */
    @Test
    @DisplayName("OidcUser가 아니다 — ID 토큰이 없는데 있다고 말하지 않는다")
    void isNotOidcUser() {
        assertThat(principal(UserStatus.ACTIVE)).isNotInstanceOf(OidcUser.class);
    }

    private CustomOAuth2User principal(UserStatus status) {
        OAuth2User delegate = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_DELEGATE_ONLY")), ATTRIBUTES, "sub");

        return new CustomOAuth2User(delegate,
                new AuthenticatedUser("public-id-1", UserRole.ROLE_USER, status));
    }
}
