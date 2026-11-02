package com.harucut.config;

import com.harucut.auth.security.AccountStateGuard;
import com.harucut.auth.security.AccountStateInterceptor;
import com.harucut.terms.service.TermsConsentGuard;
import com.harucut.terms.web.TermsConsentInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TermsConsentGuard termsConsentGuard;
    private final AccountStateGuard accountStateGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccountStateInterceptor(accountStateGuard));   // 정지가 약관 재동의 안내보다 앞선다
        registry.addInterceptor(new TermsConsentInterceptor(termsConsentGuard));
    }
}
