package com.harucut.auth.email;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.mail.MailService;
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
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.IContext;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@harucut.com";
    private static final String CODE = "ABC234";
    private static final String HTML = "<html>ABC234</html>";
    private static final String TEMPLATE = "mail/verification-code";

    @Mock
    private VerificationCodeGenerator generator;

    @Mock
    private EmailVerificationRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MailService mailService;

    @Mock
    private ITemplateEngine templateEngine;

    @Mock
    private EmailRateLimit emailRateLimit;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                generator,
                repository,
                emailRateLimit,
                userRepository,
                mailService,
                templateEngine
        );
    }

    @Nested
    @DisplayName("sendVerificationCode")
    class SendVerificationCode {

        @Test
        @DisplayName("생성한 코드를 email:code 키에 5분 TTL로 저장한다")
        void storesCodeWithTtl() {
            givenCooldownAcquired(EMAIL);
            given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(false);
            given(generator.generate()).willReturn(CODE);

            service.sendVerificationCode(EMAIL);

            then(repository).should().saveCode(EMAIL, CODE);
            then(emailRateLimit).should(never()).releaseCooldown(any());
        }

        @Test
        @DisplayName("렌더링한 템플릿 본문으로 메일을 발송한다")
        void sendsRenderedTemplate() {
            givenCooldownAcquired(EMAIL);
            given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(false);
            given(templateEngine.process(eq(TEMPLATE), any(IContext.class))).willReturn(HTML);

            service.sendVerificationCode(EMAIL);

            then(mailService).should().sendHtml(eq(EMAIL), any(), eq(HTML));
        }

        @Test
        @DisplayName("쿨다운 중이면 AUTH-040을 던지고 메일을 보내지 않는다")
        void rejectedWhileCooldown() {
            given(emailRateLimit.tryAcquireCooldown(EMAIL)).willReturn(false);

            assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.TOO_MANY_REQUESTS);

            then(repository).shouldHaveNoInteractions();
            then(userRepository).shouldHaveNoInteractions();
            then(mailService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 AUTH-030을 던지고 코드를 저장하지 않는다")
        void rejectsAlreadyRegistered() {
            givenCooldownAcquired(EMAIL);
            given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(true);

            assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.EMAIL_ALREADY_IN_USE);

            then(repository).shouldHaveNoInteractions();
            then(mailService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("SMTP 전송이 실패하면 MailException을 AUTH-090으로 감싼다")
        void wrapsMailFailure() {
            givenCooldownAcquired(EMAIL);
            given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(false);
            willThrow(new MailSendException("smtp down"))
                    .given(mailService).sendHtml(any(), any(), any());

            assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.EMAIL_SEND_FAILED);
        }

        @Test
        @DisplayName("SMTP 전송이 실패하면 쿨다운을 풀어 즉시 재요청을 허용한다")
        void releasesCooldownOnMailFailure() {
            givenCooldownAcquired(EMAIL);
            given(userRepository.existsByProviderAndEmail(Provider.HARUCUT, EMAIL)).willReturn(false);
            willThrow(new MailSendException("smtp down"))
                    .given(mailService).sendHtml(any(), any(), any());

            assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.EMAIL_SEND_FAILED);

            then(emailRateLimit).should().releaseCooldown(EMAIL);
        }
    }

    @Nested
    @DisplayName("verifyCode")
    class VerifyCode {
        @Test
        @DisplayName("코드가 일치하면 code를 지우고 verified를 10분 TTL로 심는다")
        void promotesToVerified() {
            given(repository.findCode(EMAIL)).willReturn(Optional.of(CODE));

            service.verifyCode(EMAIL, CODE);

            then(repository).should().removeCode(EMAIL);
            then(repository).should().markVerified(EMAIL);
        }

        @Test
        @DisplayName("소문자로 입력해도 검증에 성공한다")
        void verifiesIgnoringCase() {
            given(repository.findCode(EMAIL)).willReturn(Optional.of(CODE));

            service.verifyCode(EMAIL, CODE.toLowerCase());

            then(repository).should().markVerified(EMAIL);
        }

        @Test
        @DisplayName("코드가 틀리면 AUTH-003을 던지되 재시도할 수 있게 code를 남긴다")
        void wrongCodeKeepsCodeForRetry() {
            given(repository.findCode(EMAIL)).willReturn(Optional.of(CODE));

            assertThatThrownBy(() -> service.verifyCode(EMAIL, "ZZZZZZ"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_CODE);

            then(repository).should(never()).removeCode(any());
            then(repository).should(never()).markVerified(any());
        }

        @Test
        @DisplayName("코드가 만료되거나 발급된 적이 없으면 AUTH-003을 던진다")
        void missingCode() {
            given(repository.findCode(EMAIL)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyCode(EMAIL, CODE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_CODE);

            then(repository).should(never()).markVerified(any());
        }
    }

    @Nested
    @DisplayName("requireVerified / clearVerified")
    class VerifiedFlag {

        @Test
        @DisplayName("verified 키가 있으면 통과시키되 지우지 않는다")
        void passesWithoutClearing() {
            given(repository.isVerified(EMAIL)).willReturn(true);

            assertThatCode(() -> service.requireVerified(EMAIL))
                    .doesNotThrowAnyException();

            then(repository).should(never()).removeVerified(any());
        }

        @Test
        @DisplayName("verified 키가 없으면 AUTH-004를 던진다")
        void rejectsUnverified() {
            given(repository.isVerified(EMAIL)).willReturn(false);

            assertThatThrownBy(() -> service.requireVerified(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }

        @Test
        @DisplayName("clearVerified는 상태를 보지 않고 지운다")
        void clearsWithoutChecking() {
            service.clearVerified(EMAIL);

            then(repository).should().removeVerified(EMAIL);
            then(repository).should(never()).isVerified(any());
        }
    }

    private void givenCooldownAcquired(String email) {
        given(emailRateLimit.tryAcquireCooldown(email)).willReturn(true);
    }

    @Test
    @DisplayName("코드를 틀린 횟수가 상한을 넘으면 코드를 지우고 AUTH-040 — 대입으로 뚫지 못한다")
    void removesCodeWhenMissesExceeded() {
        org.mockito.BDDMockito.given(repository.findCode("a@b.c")).willReturn(java.util.Optional.of("ABCDEF"));
        org.mockito.BDDMockito.given(emailRateLimit.codeAttemptsExceeded("a@b.c")).willReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyCode("a@b.c", "ZZZZZZ"))
                .isInstanceOf(com.harucut.common.exception.BusinessException.class)
                .hasMessageContaining("Too many");
        org.mockito.BDDMockito.then(repository).should().removeCode("a@b.c");
    }

    @Test
    @DisplayName("일일 발송 상한을 넘으면 보내지 않고 GEN-061 — 이미 보낸 코드를 덮어쓰지 않고, 그 주소의 쿨다운은 풀어 준다")
    void stopsAtDailyLimit() {
        org.mockito.BDDMockito.given(emailRateLimit.tryAcquireCooldown("a@b.c")).willReturn(true);
        org.mockito.BDDMockito.given(emailRateLimit.dailySendLimitExceeded()).willReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sendVerificationCode("a@b.c"))
                .isInstanceOf(com.harucut.common.exception.BusinessException.class);
        org.mockito.BDDMockito.then(repository).should(org.mockito.Mockito.never()).saveCode(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.BDDMockito.then(mailService).shouldHaveNoInteractions();
        org.mockito.BDDMockito.then(emailRateLimit).should().releaseCooldown("a@b.c");
    }
}
