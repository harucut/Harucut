package com.harucut.config;

import com.harucut.booth.exception.BoothErrorCode;
import com.harucut.booth.repository.BoothDeviceRepository;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.Response;
import com.harucut.participant.config.ParticipationProperties;
import com.harucut.participant.exception.ParticipantErrorCode;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.participant.repository.ParticipantSessionRepository;
import com.harucut.result.exception.ResultErrorCode;
import com.harucut.result.repository.ResultAccessRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * 행사 참가·부스·결과 전용 보안 체인 (ADR-0007 §2). 회원 체인(SecurityConfig)보다 먼저 일치한다.
 *
 * <p>회원 JWT 필터·OAuth2 로그인이 없다. 쿠키를 쓰지 않으므로 CSRF 대상이 아니다. 응답은 전부 no-store,
 * 리퍼러는 보내지 않는다(결과 토큰이 다른 사이트로 새지 않게). 매처에서 빠진 경로는 회원 체인이 막는다.
 */
@Configuration
public class EventSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain eventFilterChain(HttpSecurity http,
                                                ParticipantSessionRepository sessionRepository,
                                                ParticipantRepository participantRepository,
                                                ResultAccessRepository resultAccessRepository,
                                                BoothDeviceRepository boothDeviceRepository,
                                                RateLimiter rateLimiter,
                                                ParticipationProperties participationProperties,
                                                PlatformTransactionManager transactionManager,
                                                Clock clock,
                                                ObjectMapper objectMapper) throws Exception {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        http
                .securityMatcher(SecurityPaths.EVENT)
                // 프론트(다른 출처)가 X-Participant-Token 같은 사용자 정의 헤더를 실으니 preflight 를 받아야 한다 — 회원 체인과 같은 출처 목록
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .cacheControl(cache -> {
                        })
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> write(objectMapper, response, codeFor(request)))
                        .accessDeniedHandler((request, response, e) -> write(objectMapper, response, codeFor(request))))
                .addFilterBefore(new EventCredentialFilter(sessionRepository, participantRepository,
                        resultAccessRepository, boothDeviceRepository,
                        rateLimiter, participationProperties.publicRequestsPerMinute(), readOnly, clock), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/public/events/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/events/*/sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/booth-pairings/redeem").permitAll()
                        .requestMatchers("/api/participant/**").hasRole("PARTICIPANT")
                        .requestMatchers("/api/public/results/**").hasRole("RESULT_ACCESS")
                        .requestMatchers("/api/booth/**").hasRole("BOOTH")
                        .anyRequest().denyAll());
        return http.build();
    }

    // 자격이 없거나 무효일 때 경로에 맞는 코드
    private static ErrorCode codeFor(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(EventCredentialFilter.RATE_LIMITED))) {
            return GlobalErrorCode.TOO_MANY_REQUESTS;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/participant/")) {
            return ParticipantErrorCode.SESSION_INVALID;
        }
        if (path.startsWith("/api/public/results")) {
            return ResultErrorCode.TOKEN_INVALID;
        }
        if (path.startsWith("/api/booth/")) {
            return BoothErrorCode.DEVICE_UNAVAILABLE;
        }
        return GlobalErrorCode.FORBIDDEN;
    }

    private static void write(ObjectMapper objectMapper, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Response.error(code));
    }
}
