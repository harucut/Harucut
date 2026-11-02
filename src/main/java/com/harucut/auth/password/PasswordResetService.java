package com.harucut.auth.password;

import com.harucut.auth.email.EmailRateLimit;
import com.harucut.auth.email.VerificationCodeGenerator;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.service.RefreshTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.mail.MailService;
import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String SUBJECT = "[Harucut] 비밀번호 재설정 코드입니다.";
    private static final String TEMPLATE = "mail/password-reset-code";

    private final VerificationCodeGenerator generator;
    private final PasswordResetRepository repository;
    private final EmailRateLimit emailRateLimit;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final MailService mailService;
    private final ITemplateEngine templateEngine;

    public void sendResetCode(String email) {
        if (!emailRateLimit.tryAcquireCooldown(email)) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_REQUESTS);
        }

        if (!userRepository.existsByProviderAndEmail(Provider.HARUCUT, email)) {
            throw new BusinessException(AuthErrorCode.USER_NOT_FOUND);
        }

        String code = generator.generate();
        repository.saveCode(email, code);

        Context context = new Context();
        context.setVariable("code", code);
        String html = templateEngine.process(TEMPLATE, context);

        if (emailRateLimit.dailySendLimitExceeded()) {
            emailRateLimit.releaseCooldown(email);
            throw new BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS);
        }

        try {
            mailService.sendHtml(email, SUBJECT, html);
        } catch (MailException e) {
            emailRateLimit.releaseCooldown(email);
            throw new BusinessException(AuthErrorCode.EMAIL_SEND_FAILED);
        }
    }

    public String verifyResetCode(String email, String code) {
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

        String resetToken = UUID.randomUUID().toString();
        repository.saveToken(resetToken, email);

        return resetToken;
    }

    @Transactional
    public void resetPassword(String resetToken, String newPassword) {
        String email = repository.consumeToken(resetToken)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));

        User user = userRepository.findByProviderAndEmail(Provider.HARUCUT, email)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));

        refreshTokenService.revokeAll(user.getPublicId());
        user.changePassword(passwordEncoder.encode(newPassword));
    }
}
