package com.harucut.auth.password;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.BusinessException;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

    private static final String PUBLIC_ID = "abcdefghijkl";
    private static final String OLD_PASSWORD = "oldpassword123";
    private static final String NEW_PASSWORD = "newpassword456";

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.harucut.auth.service.RefreshTokenService refreshTokenService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(userRepository, passwordEncoder, refreshTokenService);
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        @Test
        @DisplayName("기존 비밀번호가 맞으면 새 비밀번호를 인코딩해서 교체한다")
        void changesPassword() {
            User user = givenLocalUser();

            service.changePassword(PUBLIC_ID, OLD_PASSWORD, NEW_PASSWORD);

            assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPassword())).isTrue();
            assertThat(passwordEncoder.matches(OLD_PASSWORD, user.getPassword())).isFalse();
        }

        @Test
        @DisplayName("기존 비밀번호가 틀리면 AUTH-002를 던지고 비밀번호를 바꾸지 않는다")
        void rejectsWrongOldPassword() {
            User user = givenLocalUser();
            String before = user.getPassword();

            assertThatThrownBy(() -> service.changePassword(PUBLIC_ID, "wrong-password", NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INCORRECT_PASSWORD);

            assertThat(user.getPassword()).isEqualTo(before);
        }

        @Test
        @DisplayName("소셜 계정이면 비밀번호 비교 전에 AUTH-008을 던진다")
        void rejectsSocialAccount() {
            User user = UserFixtures.socialUser("user@harucut.com", Provider.KAKAO);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.changePassword(PUBLIC_ID, OLD_PASSWORD, NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.SOCIAL_ACCOUNT_NO_PASSWORD);

            assertThat(user.getPassword()).isNull();
        }

        @Test
        @DisplayName("사용자가 없으면 AUTH-020을 던진다")
        void rejectsUnknownUser() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword(PUBLIC_ID, OLD_PASSWORD, NEW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.USER_NOT_FOUND);
        }
    }


    private User givenLocalUser() {
        User user = UserFixtures.localUser("user@harucut.com", passwordEncoder.encode(OLD_PASSWORD));
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
        return user;
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 모든 기기 세션을 끊는다")
    void revokesAllSessions() {
        givenLocalUser();

        service.changePassword(PUBLIC_ID, OLD_PASSWORD, NEW_PASSWORD);

        org.mockito.BDDMockito.then(refreshTokenService).should().revokeAll(PUBLIC_ID);
    }

    @Test
    @DisplayName("옛 비밀번호가 틀리면 세션을 건드리지 않는다")
    void wrongOldPasswordKeepsSessions() {
        givenLocalUser();

        assertThatThrownBy(() -> service.changePassword(PUBLIC_ID, "wrong-password", NEW_PASSWORD))
                .isInstanceOf(com.harucut.common.exception.BusinessException.class);

        org.mockito.BDDMockito.then(refreshTokenService).shouldHaveNoInteractions();
    }
}
