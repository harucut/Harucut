package com.harucut.auth.oauth2.app;

import com.harucut.auth.service.RefreshTokenService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.harucut.auth.oauth2.app.AppLoginFixtures.CHALLENGE;
import static com.harucut.auth.oauth2.app.AppLoginFixtures.VERIFIER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 교환 API 를 실제 보안 체인·컨트롤러로 — 로그인 없이 열린 경로이고, 성공하면 /api/harucut/login 과 같은 두 쿠키·본문.
 * Redis 는 테스트에 없어 코드 저장소만 목으로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("앱 로그인 코드 교환 — 통합")
class AppLoginExchangeIntegrationTest {

    @Autowired private MockMvcTester mvc;
    @Autowired private UserRepository userRepository;

    @MockitoBean private AppLoginCodeRepository codeRepository;
    @MockitoBean private RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("코드·verifier 가 맞으면 200, 로그인과 같은 두 HttpOnly 쿠키와 userStatus")
    void exchange() {
        User user = userRepository.save(User.localUser("app@harucut.com", "e", "앱"));
        given(codeRepository.consume("one-time")).willReturn(Optional.of(new AppLoginCodeRepository.Grant(user.getPublicId(), CHALLENGE)));

        MvcTestResult result = exchange("one-time", VERIFIER);

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("accessToken=").contains("HttpOnly"))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=").contains("HttpOnly"));
        assertThat(result).bodyJson().extractingPath("$.data.userStatus").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("없거나 쓴 코드·틀린 verifier 는 400 AUTH-041, 쿠키 없음")
    void invalid() {
        given(codeRepository.consume(anyString())).willReturn(Optional.empty());
        MvcTestResult unknown = exchange("used", VERIFIER);
        assertThat(unknown).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("AUTH-041");
        assertThat(unknown.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        given(codeRepository.consume("one-time")).willReturn(Optional.of(new AppLoginCodeRepository.Grant("u", CHALLENGE)));
        assertThat(exchange("one-time", "x".repeat(43))).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("AUTH-041");
    }

    @Test
    @DisplayName("verifier 형식이 틀리면(43자 미만·허용 밖 문자) 400 GEN-003 — 코드를 꺼내지도 않는다")
    void malformed() {
        assertThat(exchange("one-time", "short")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("GEN-003");
        assertThat(exchange("one-time", "가".repeat(43))).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("GEN-003");
    }

    @Test
    @DisplayName("시작: 실제 보안 체인에서 client=app·code_challenge 가 세션의 인가 요청 속성에 실린 채 제공자로 302")
    void startCarriesAppMarker() {
        MvcTestResult result = mvc.get().uri("/oauth2/authorization/google?client=app&code_challenge=" + CHALLENGE).exchange();

        assertThat(result).hasStatus(302);
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://accounts.google.com/");
        Object stored = result.getRequest().getSession().getAttribute(
                org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository.class.getName()
                        + ".AUTHORIZATION_REQUEST");
        assertThat(stored).isInstanceOfSatisfying(org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.class,
                authorization -> {
                    assertThat(authorization.<String>getAttribute(AppLoginRequest.CLIENT_ATTRIBUTE)).isEqualTo("app");
                    assertThat(authorization.<String>getAttribute(AppLoginRequest.CHALLENGE_ATTRIBUTE)).isEqualTo(CHALLENGE);
                });
    }

    private MvcTestResult exchange(String code, String verifier) {
        return mvc.post().uri("/api/harucut/oauth2/exchange").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"codeVerifier\":\"" + verifier + "\"}").exchange();
    }
}
