package com.harucut.config;

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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TermsConsentInterceptor(termsConsentGuard));
    }
}
