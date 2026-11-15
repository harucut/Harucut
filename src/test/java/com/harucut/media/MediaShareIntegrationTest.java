package com.harucut.media;

import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.config.RateLimiter;
import com.harucut.media.entity.UserMedia;
import com.harucut.media.handler.UserMediaDeletionHandler;
import com.harucut.media.repository.MediaShareRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/*
 * 개인 사진 공유 링크(/s/<token>, docs/decisions.md 2026-11-16) — 실제 두 보안 체인을 지난다.
 * 만들기는 회원 체인(쿠키), 열기는 행사 체인(Bearer 공유 토큰). 링크는 사진이 주인 기록에 보이는 동안만 열린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("사진 공유 링크 — 통합")
class MediaShareIntegrationTest {

    private static final String OPEN = "/api/public/media-shares";

    @Autowired private MockMvcTester mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserMediaRepository userMediaRepository;
    @Autowired private MediaShareRepository mediaShareRepository;
    @Autowired private UserMediaDeletionHandler userMediaDeletionHandler;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private EntityManager entityManager;
    @Autowired private Clock clock;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private RateLimiter rateLimiter;

    private User owner;
    private Long mediaId;
    private String key;
    private Cookie ownerCookie;

    @BeforeEach
    void setUp() {
        given(rateLimiter.tryAcquire(anyString(), anyString(), anyInt(), any())).willReturn(true);
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/view-short");
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/save-short");
        owner = userRepository.save(User.localUser("sharer@harucut.com", "e", "공유하는 사람"));
        key = S3Keys.userRoot(owner.getPublicId()) + "fourcuts/job-1.jpg";
        mediaId = userMediaRepository.save(UserMedia.of(owner, key, "우리 넷.jpg")).getId();
        ownerCookie = new Cookie(CookieManager.ACCESS_TOKEN,
                jwtTokenService.createAccessToken(owner.getPublicId(), UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }

    @Test
    @DisplayName("같은 사진은 몇 번을 눌러도 같은 링크 — 받는 사람은 로그인 없이 Bearer 로 짧은 화면·저장 URL 을 받고, 응답은 캐시·리퍼러가 없다")
    void sameLinkOpensWithoutLogin() {
        String token = link(mediaId);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(link(mediaId)).isEqualTo(token);

        MvcTestResult opened = open(token);
        JsonNode photo = data(opened, 200);
        assertThat(photo.path("displayName").asString()).isEqualTo("우리 넷.jpg");
        assertThat(photo.path("createdAt").asString()).isNotBlank();
        assertThat(photo.path("imageUrl").asString()).isEqualTo("https://s3/view-short");
        assertThat(photo.path("downloadUrl").asString()).isEqualTo("https://s3/save-short");
        assertThat(opened.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(opened.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        then(fileStorageService).should().generatePresignedGetUrl(key, Duration.ofMinutes(5));
        then(fileStorageService).should().generatePresignedDownloadUrl(key, "우리 넷.jpg", Duration.ofMinutes(5));

        // 사진마다 따로다
        Long other = userMediaRepository.save(UserMedia.of(owner, S3Keys.userRoot(owner.getPublicId()) + "fourcuts/job-2.jpg", "둘째.jpg")).getId();
        assertThat(link(other)).isNotEqualTo(token);
    }

    @Test
    @DisplayName("닫힌 이유를 가르지 않는다 — 토큰 없음·틀린 토큰·회원 쿠키·사진 삭제가 모두 404 MEDIA-002, 공유 토큰은 결과 자격도 아니다")
    void closedLooksTheSame() {
        String token = link(mediaId);

        assertClosed(mvc.get().uri(OPEN).exchange());
        assertClosed(open(token.substring(1) + "x"));
        assertClosed(open(swapCase(token)));
        assertClosed(mvc.get().uri(OPEN).cookie(ownerCookie).exchange());
        assertCode(mvc.get().uri("/api/public/results").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange(),
                401, "RESULT-001");

        data(mvc.delete().uri("/api/auth/user/media/{id}", mediaId).cookie(ownerCookie).exchange(), 200);
        assertClosed(open(token));
        assertThat(mediaShareRepository.findByMediaId(mediaId)).isEmpty();
    }

    @Test
    @DisplayName("남의 사진은 링크를 못 만든다(404 GEN-031), 로그인 없이도 못 만든다(401)")
    void onlyOwnerCreates() {
        User stranger = userRepository.save(User.localUser("stranger@harucut.com", "e", "남"));
        Cookie strangerCookie = new Cookie(CookieManager.ACCESS_TOKEN,
                jwtTokenService.createAccessToken(stranger.getPublicId(), UserRole.ROLE_USER, UserStatus.ACTIVE).value());

        assertCode(mvc.post().uri("/api/auth/user/media/{id}/share-link", mediaId).cookie(strangerCookie).exchange(), 404, "GEN-031");
        assertThat(mvc.post().uri("/api/auth/user/media/{id}/share-link", mediaId).exchange()).hasStatus(401);
        assertThat(mediaShareRepository.findByMediaId(mediaId)).isEmpty();
    }

    @Test
    @DisplayName("주인 계정을 쓸 수 없으면 닫히고, 다시 쓸 수 있으면 같은 링크가 열린다 — 정지·탈퇴 요청(보관 기간은 지금 세 요금제 모두 무제한이라 단위 시험이 본다)")
    void followsWhatTheOwnerSees() {
        String token = link(mediaId);

        changeOwner(user -> user.block(LocalDateTime.now(clock)));
        assertClosed(open(token));
        changeOwner(User::unblock);
        data(open(token), 200);

        changeOwner(user -> user.deleteRequested(LocalDateTime.now(clock)));
        assertClosed(open(token));
        changeOwner(User::reActivate);
        data(open(token), 200);
    }

    @Test
    @DisplayName("틀린 공유 토큰이 같은 IP 에서 한도를 넘으면 429 GEN-061 — 토큰이 없는 요청과 맞는 토큰은 세지 않는다")
    void wrongTokensAreRateLimited() {
        String token = link(mediaId);
        given(rateLimiter.tryAcquire(eq("media-share-miss"), anyString(), anyInt(), any())).willReturn(false);

        assertCode(open("guessed"), 429, "GEN-061");
        assertClosed(mvc.get().uri(OPEN).exchange());
        data(open(token), 200);
        then(rateLimiter).should().tryAcquire(eq("media-share-miss"), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("탈퇴 처리는 사진보다 공유 링크를 먼저 지운다 — 링크가 닫힌다")
    void accountDeletionRemovesLinks() {
        String token = link(mediaId);

        userMediaDeletionHandler.handleUserDeletion(owner.getId());

        assertThat(mediaShareRepository.findByToken(token)).isEmpty();
        assertClosed(open(token));
    }

    private String link(Long id) {
        return data(mvc.post().uri("/api/auth/user/media/{id}/share-link", id).cookie(ownerCookie).exchange(), 200)
                .path("token").asString();
    }

    private MvcTestResult open(String token) {
        return mvc.get().uri(OPEN).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private void changeOwner(Consumer<User> change) {
        change.accept(userRepository.findById(owner.getId()).orElseThrow());
        entityManager.flush();
        entityManager.clear();
    }

    private void assertClosed(MvcTestResult result) {
        assertCode(result, 404, "MEDIA-002");
    }

    private static String swapCase(String token) {
        StringBuilder swapped = new StringBuilder();
        token.chars().forEach(c -> swapped.append((char) (Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c))));
        return swapped.toString();
    }

    private JsonNode data(MvcTestResult result, int status) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        return body.path("data");
    }

    private void assertCode(MvcTestResult result, int status, String code) {
        JsonNode body = body(result);
        assertThat(result.getResponse().getStatus()).as(body.toString()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
