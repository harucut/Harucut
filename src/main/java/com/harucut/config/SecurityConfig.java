package com.harucut.config;

import com.harucut.auth.oauth2.CustomOAuth2FailureHandler;
import com.harucut.auth.oauth2.CustomOAuth2SuccessHandler;
import com.harucut.auth.oauth2.CustomOAuth2UserService;
import com.harucut.auth.oauth2.CustomOidcUserService;
import com.harucut.auth.oauth2.app.AppAwareAuthorizationRequestResolver;
import com.harucut.auth.oauth2.app.RememberingAuthorizationRequestRepository;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.security.JwtAuthenticationFilter;
import com.harucut.auth.security.OriginCheckFilter;
import com.harucut.auth.service.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private final JwtTokenService jwtTokenService;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomOAuth2UserService oAuth2UserService;
    private final CustomOidcUserService oidcUserService;
    private final CustomOAuth2SuccessHandler oAuth2SuccessHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final CustomOAuth2FailureHandler oAuth2FailureHandler;
    private final CorsProperties corsProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                // 스프링 CSRF 토큰 대신 쿠키 인증 변경 요청의 Origin 을 본다(OriginCheckFilter, R5 D07)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .oauth2Login(oauth2 -> oauth2
                        // 앱 로그인(client=app·PKCE) 표시를 인가 요청에 싣고, 콜백에서 꺼낸 인가 요청을 핸들러가 볼 수 있게 남긴다
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(new AppAwareAuthorizationRequestResolver(clientRegistrationRepository))
                                .authorizationRequestRepository(new RememberingAuthorizationRequestRepository()))
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(oAuth2UserService)
                                .oidcUserService(oidcUserService))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler))

                .exceptionHandling(handling ->
                        handling
                                .authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler)
                )

                .addFilterBefore(
                        new JwtAuthenticationFilter(SecurityPaths.publicMatcher(), jwtTokenService, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class
                )
                // CORS·인증보다 먼저 — 다른 출처에서 온 쿠키 요청은 토큰을 읽기 전에 GEN-022 로 거절한다
                .addFilterBefore(
                        new OriginCheckFilter(corsProperties.allowedOrigins(), SecurityPaths.originCheckExemptMatcher(), objectMapper),
                        CorsFilter.class
                )

                .authorizeHttpRequests(auth -> auth
                        // Prometheus 가 긁어갈 경로. 인증 수단이 없으니 통과시킨다.
                        // 노출을 막는 것은 네트워크 쪽에서 한다 — management.server.port 로
                        // API 포트(8080)와 분리하고, 관리 포트는 호스트로 publish 하지 않는다.
                        // API 포트에는 actuator 가 매핑되지 않으므로 여기서 열려도 404 다.
                        //
                        // SecurityPaths.PUBLIC 에 넣지 않는 이유: 그 배열은 OpenAPI 문서의
                        // 자물쇠 표시도 파생시킨다. API 경로 규칙에 관리 경로를 섞지 않는다.
                        .requestMatchers("/actuator/**").permitAll()
                        // 넓은 로그인 규칙(anyRequest)보다 앞에 둔다 — 먼저 일치한 규칙이 이긴다
                        .requestMatchers(SecurityPaths.ADMIN).hasRole("ADMIN")
                        .requestMatchers(SecurityPaths.PUBLIC).permitAll()
                        .requestMatchers(SecurityPaths.AUTHENTICATED_ONLY).authenticated()
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(corsProperties.allowedOrigins());

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
