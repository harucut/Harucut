package com.harucut.auth.password;

import com.harucut.auth.email.EmailRateLimit;
import com.harucut.auth.email.VerificationCodeGenerator;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.mail.MailService;
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
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String EMAIL = "user@harucut.com";
    private static final String CODE = "ABC234";
    private static final String HTML = "<html>ABC234</html>";
    private static final String TEMPLATE = "mail/password-reset-code";
    private static final String RAW_PASSWORD = "newpassword123";

    @Mock
    private VerificationCodeGenerator generator;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private EmailRateLimit emailRateLimit;

    @Mock
    private PasswordResetRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MailService mailService;

    @Mock
    private ITemplateEngine templateEngine;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(
                generator,
                repository,
                emailRateLimit,
                userRepository,
                passwordEncoder,
                refreshTokenService,
                mailService,
                templateEngine
        );
    }

    @Nested
    @DisplayName("sendResetCode")
    class SendResetCode {

        @Test
        @DisplayName("생성한 코드를 email:reset:code 키로 저장한다")
        void storesCodeWithTtl() {
            givenCooldownAcquired();
            givenRegistered(true);
            given(generator.generate()).willReturn(CODE);

            service.sendResetCode(EMAIL);

            then(repository).should().saveCode(EMAIL, CODE);
            then(emailRateLimit).should(never()).releaseCooldown(any());
        }

        @Test
        @DisplayName("재설정 템플릿을 렌더해서 발송한다")
        void sendsResetTemplate() {
            givenCooldownAcquired();
            givenRegistered(true);
            given(templateEngine.process(eq(TEMPLATE), any(IContext.class))).willReturn(HTML);

            service.sendResetCode(EMAIL);

            then(mailService).should().sendHtml(eq(EMAIL), any(), eq(HTML));
        }

        @Test
        @DisplayName("가입되지 않은 이메일이면 AUTH-020을 던지고 코드를 저장하지 않는다")
        void rejectsUnknownEmail() {
            givenCooldownAcquired();
            givenRegistered(false);

            assertThatThrownBy(() -> service.sendResetCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.USER_NOT_FOUND);

            then(repository).shouldHaveNoInteractions();
            then(mailService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("쿨다운 중이면 AUTH-040을 던지고 가입 여부도 보지 않는다")
        void rejectedWhileCooldown() {
            given(emailRateLimit.tryAcquireCooldown(EMAIL)).willReturn(false);

            assertThatThrownBy(() -> service.sendResetCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.TOO_MANY_REQUESTS);

            then(userRepository).shouldHaveNoInteractions();
            then(repository).shouldHaveNoInteractions();
            then(mailService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("SMTP 전송이 실패하면 AUTH-090으로 감싸고 쿨다운을 푼다")
        void releasesCooldownOnMailFailure() {
            givenCooldownAcquired();
            givenRegistered(true);
            willThrow(new MailSendException("smtp down"))
                    .given(mailService).sendHtml(any(), any(), any());

            assertThatThrownBy(() -> service.sendResetCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.EMAIL_SEND_FAILED);

            then(emailRateLimit).should().releaseCooldown(EMAIL);
        }
    }

    @Nested
    @DisplayName("verifyResetCode")
    class VerifyResetCode {

        @Test
        @DisplayName("코드가 맞으면 code를 지우고 리셋 토큰을 email에 10분 TTL로 매핑한다")
        void issuesTokenMappedToEmail() {
            given(repository.findCode(EMAIL)).willReturn(Optional.of(CODE));

            String token = service.verifyResetCode(EMAIL, CODE);

            then(repository).should().removeCode(EMAIL);
            then(repository).should().saveToken(token, EMAIL);
        }

        @Test
        @DisplayName("소문자로 입력해도 검증에 성공한다")
        void verifiesIgnoringCase() {
            given(repository.findCode(EMAIL)).willReturn(Optional.of(CODE.toLowerCase()));

            String token = service.verifyResetCode(EMAIL, CODE);

            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("코드가 틀리면 AUTH-003을 던지되 재시도할 수 있게 code를 남긴다")
        void wrongCodeKeepsCodeForRetry() {
            given(repository.findCode(EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyResetCode(EMAIL, CODE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_CODE);

            then(repository).should(never()).removeCode(any());
            then(repository).should(never()).saveToken(any(), any());
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        private static final String TOKEN = "550e8400-e29b-41d4-a716-446655440000";

        @Test
        @DisplayName("토큰을 GETDEL로 소비하고 새 비밀번호를 인코딩해서 저장한다")
        void consumesTokenAndEncodes() {
            User user = givenTokenMapsToUser();

            service.resetPassword(TOKEN, RAW_PASSWORD);

            then(repository).should().consumeToken(TOKEN);
            assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPassword())).isTrue();
        }

        @Test
        @DisplayName("재설정하면 기존 refresh 토큰을 끊는다")
        void revokesRefreshToken() {
            User user = givenTokenMapsToUser();

            service.resetPassword(TOKEN, RAW_PASSWORD);

            then(refreshTokenService).should().revoke(user.getPublicId());
        }

        @Test
        @DisplayName("토큰이 없거나 만료됐거나 이미 쓴 것이면 AUTH-011을 던진다")
        void rejectsUnusableToken() {
            given(repository.consumeToken(TOKEN)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword(TOKEN, RAW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_TOKEN);

            then(userRepository).shouldHaveNoInteractions();
            then(refreshTokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("매핑된 이메일의 로컬 계정이 없으면 AUTH-020을 던진다")
        void rejectsMissingUser() {
            given(repository.consumeToken(TOKEN)).willReturn(Optional.of(EMAIL));
            given(userRepository.findByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword(TOKEN, RAW_PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.USER_NOT_FOUND);

            then(refreshTokenService).shouldHaveNoInteractions();
        }

        private User givenTokenMapsToUser() {
            User user = UserFixtures.localUser(EMAIL, "$2a$04$oldhasholdhasholdhash");
            given(repository.consumeToken(TOKEN)).willReturn(Optional.of(EMAIL));
            given(userRepository.findByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(Optional.of(user));
            return user;
        }
    }

    private void givenCooldownAcquired() {
        given(emailRateLimit.tryAcquireCooldown(EMAIL)).willReturn(true);
    }

    private void givenRegistered(boolean registered) {
        given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(registered);
    }

    @Test
    @DisplayName("재설정 코드를 틀린 횟수가 상한을 넘으면 코드를 지우고 AUTH-040 — 대입으로 계정을 뺏지 못한다")
    void removesResetCodeWhenMissesExceeded() {
        org.mockito.BDDMockito.given(repository.findCode("a@b.c")).willReturn(java.util.Optional.of("ABCDEF"));
        org.mockito.BDDMockito.given(emailRateLimit.codeAttemptsExceeded("a@b.c")).willReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyResetCode("a@b.c", "ZZZZZZ"))
                .isInstanceOf(com.harucut.common.exception.BusinessException.class)
                .hasMessageContaining("Too many");
        org.mockito.BDDMockito.then(repository).should().removeCode("a@b.c");
        org.mockito.BDDMockito.then(repository).should(org.mockito.Mockito.never()).saveToken(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
