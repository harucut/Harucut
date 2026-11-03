package com.harucut.auth.email;

import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.mail.MailService;
import com.harucut.common.mail.MailTemplate;
import com.harucut.user.enums.Provider;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String SUBJECT = "[Harucut] 회원가입 이메일 인증 코드입니다.";
    private static final String TEMPLATE = "templates/mail/verification-code.html";

    private final VerificationCodeGenerator generator;
    private final EmailVerificationRepository repository;
    private final EmailRateLimit emailRateLimit;
    private final UserRepository userRepository;
    private final MailService mailService;

    public void sendVerificationCode(String email) {
        if (!emailRateLimit.tryAcquireCooldown(email)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_REQUESTS);
        }

        if (userRepository.existsByProviderAndEmail(Provider.HARUCUT, email)) {
            throw new BusinessException(AuthErrorCode.EMAIL_ALREADY_IN_USE);
        }

        // 코드를 바꾸기 전에 본다 — 상한에 걸려 메일을 못 보내면서 이미 보낸 유효 코드를 덮어쓰지 않게
        if (emailRateLimit.dailySendLimitExceeded()) {
            emailRateLimit.releaseCooldown(email);
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }

        String code = generator.generate();
        repository.saveCode(email, code);

        String html = MailTemplate.render(TEMPLATE, Map.of("code", code));

        try {
            mailService.sendHtml(email, SUBJECT, html);
        } catch (MailException e) {
            emailRateLimit.releaseCooldown(email);
            throw new BusinessException(AuthErrorCode.EMAIL_SEND_FAILED);
        }
    }

    public void verifyCode(String email, String code) {
        String stored = repository.findCode(email)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_VERIFICATION_CODE));

        if (!stored.equalsIgnoreCase(code)) {
            if (emailRateLimit.codeAttemptsExceeded(email)) {
                repository.removeCode(email);
                throw new BusinessException(AuthErrorCode.TOO_MANY_REQUESTS);
            }
            throw new BusinessException(AuthErrorCode.INVALID_VERIFICATION_CODE);
        }

        repository.removeCode(email);
        repository.markVerified(email);
    }

    public void requireVerified(String email) {
        if (!repository.isVerified(email)) {
            throw new BusinessException(AuthErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    public void clearVerified(String email) {
        repository.removeVerified(email);
    }
}
