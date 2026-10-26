package com.harucut.config;

import com.harucut.auth.oauth2.CustomOAuth2FailureHandler;
import com.harucut.auth.oauth2.CustomOAuth2SuccessHandler;
import com.harucut.auth.oauth2.CustomOAuth2UserService;
import com.harucut.auth.oauth2.CustomOidcUserService;
import com.harucut.auth.security.CustomAccessDeniedHandler;
import com.harucut.auth.security.CustomAuthenticationEntryPoint;
import com.harucut.auth.security.JwtAuthenticationFilter;
import com.harucut.auth.service.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenService jwtTokenService;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomOAuth2UserService oAuth2UserService;
    private final CustomOidcUserService oidcUserService;
    private final CustomOAuth2SuccessHandler oAuth2SuccessHandler;
    private final CustomOAuth2FailureHandler oAuth2FailureHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .oauth2Login(oauth2 -> oauth2
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

        config.setAllowedOrigins(List.of(
                "https://harucut.com",
                "https://www.harucut.com",
                "http://localhost:5173",
                "http://localhost:3000"
        ));

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
