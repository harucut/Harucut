package com.harucut.push;

import com.harucut.auth.service.JwtTokenService;
import com.harucut.push.batch.SubscriptionExpiringNoticeService;
import com.harucut.push.repository.DeviceTokenRepository;
import com.harucut.push.service.PushRequestedEvent;
import com.harucut.push.service.PushType;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 푸시 기기 등록·해제와 만료 예고 배치 — 실제 보안 체인·컨트롤러·JPA. 발송 자체는 커밋 뒤라 이 테스트(롤백)에서는 나가지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@DisplayName("푸시 — 통합")
class PushIntegrationTest {

    private static final String TOKEN = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";

    @Autowired private MockMvcTester mvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSubscriptionRepository subscriptionRepository;
    @Autowired private DeviceTokenRepository deviceTokenRepository;
    @Autowired private SubscriptionExpiringNoticeService noticeService;
    @Autowired private Clock clock;
    @Autowired private ApplicationEvents events;

    private User user;
    private User other;

    @BeforeEach
    void users() {
        user = save("push@harucut.com");
        other = save("push-other@harucut.com");
    }

    @Test
    @DisplayName("등록은 멱등이고, 다른 계정이 같은 토큰을 등록하면 그 계정으로 옮긴다")
    void registerIdempotentAndMoves() {
        assertThat(register(user, TOKEN, "ios")).hasStatusOk();
        assertThat(register(user, TOKEN, "ios")).hasStatusOk();
        assertThat(deviceTokenRepository.findAll()).singleElement()
                .satisfies(d -> assertThat(d.getUserId()).isEqualTo(user.getId()));

        assertThat(register(other, TOKEN, "android")).hasStatusOk();
        assertThat(deviceTokenRepository.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getUserId()).isEqualTo(other.getId());
            assertThat(d.getPlatform().name()).isEqualTo("ANDROID");
        });
    }

    @Test
    @DisplayName("계정당 10대 — 11번째를 등록하면 가장 오래 등록하지 않은 기기가 빠진다")
    void evictsOldest() {
        for (int i = 0; i < 11; i++) {
            assertThat(register(user, "ExpoPushToken[device" + i + "]", "android")).hasStatusOk();
        }
        // 0번을 다시 등록하면 최근이 된다 → 다음 등록에서 1번이 빠진다
        assertThat(register(user, "ExpoPushToken[device1]", "android")).hasStatusOk();
        assertThat(register(user, "ExpoPushToken[device11]", "android")).hasStatusOk();

        List<String> tokens = deviceTokenRepository.findTokensByUserId(user.getId());
        assertThat(tokens).hasSize(10).doesNotContain("ExpoPushToken[device0]", "ExpoPushToken[device2]")
                .contains("ExpoPushToken[device1]", "ExpoPushToken[device11]");
    }

    @Test
    @DisplayName("Expo 형식이 아닌 토큰·소문자가 아닌 플랫폼은 400 GEN-003")
    void validation() {
        assertCode(register(user, "fcm-raw-token", "ios"), 400, "GEN-003");
        assertCode(register(user, "ExponentPushToken[]", "ios"), 400, "GEN-003");
        assertCode(register(user, TOKEN, "IOS"), 400, "GEN-003");
        assertCode(register(user, TOKEN, "web"), 400, "GEN-003");
        assertThat(deviceTokenRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("해제는 내 계정의 토큰만 지우고, 없어도 200 — 탈퇴 요청 상태에서도 된다")
    void unregister() {
        register(user, TOKEN, "ios");
        String others = "ExpoPushToken[others]";
        register(other, others, "ios");

        assertThat(call(user, "/api/auth/devices/unregister", "{\"token\":\"" + others + "\"}")).hasStatusOk();
        assertThat(deviceTokenRepository.findTokensByUserId(other.getId())).containsExactly(others);

        ReflectionTestUtils.setField(user, "userStatus", UserStatus.DELETED_REQUESTED);
        assertThat(call(user, "/api/auth/devices/unregister", "{\"token\":\"" + TOKEN + "\"}")).hasStatusOk();
        assertThat(deviceTokenRepository.findTokensByUserId(user.getId())).isEmpty();
        assertThat(call(user, "/api/auth/devices/unregister", "{\"token\":\"" + TOKEN + "\"}")).hasStatusOk();
    }

    @Test
    @DisplayName("만료 예고: 3일 뒤 하루 안에 끝나는 해지·쿠폰 구독만, 같은 기간에 한 번만")
    void expiringNotice() {
        LocalDate today = LocalDate.now(clock);
        UserSubscription canceled = subscriptionRepository.findByUserId(user.getId()).orElseThrow();
        canceled.activatePaid(PlanTier.PLUS, today.minusDays(27).atStartOfDay(), today.plusDays(3).atTime(15, 0));
        canceled.cancelAutoRenew();
        UserSubscription renewing = subscriptionRepository.findByUserId(other.getId()).orElseThrow();
        renewing.activatePaid(PlanTier.PLUS, today.minusDays(27).atStartOfDay(), today.plusDays(3).atTime(15, 0));
        User granted = save("push-grant@harucut.com");
        UserSubscription grant = subscriptionRepository.findByUserId(granted.getId()).orElseThrow();
        grant.activateGrant(PlanTier.PRO, today.minusDays(10).atStartOfDay(), today.plusDays(4).atStartOfDay());
        subscriptionRepository.flush();

        // 자동 갱신 중인 구독·D+4 0시에 끝나는 쿠폰은 대상이 아니다
        assertThat(noticeService.candidates(today)).containsExactly(canceled.getId());
        assertThat(noticeService.notify(canceled.getId(), today)).isTrue();
        assertThat(noticeService.notify(canceled.getId(), today)).isFalse();
        assertThat(noticeService.notify(renewing.getId(), today)).isFalse();
        assertThat(events.stream(PushRequestedEvent.class)).singleElement().satisfies(e -> {
            assertThat(e.userId()).isEqualTo(user.getId());
            assertThat(e.message().type()).isEqualTo(PushType.SUBSCRIPTION_EXPIRING);
            assertThat(e.message().data()).containsEntry("type", "SUBSCRIPTION_EXPIRING");
        });

        // 이어 받을 쿠폰이 예약된 구독은 끝나지 않으니 보내지 않는다(잠근 뒤 다시 보는 조건)
        grant.activateGrant(PlanTier.PRO, today.atStartOfDay(), today.plusDays(3).atTime(9, 0));
        grant.reserveGrant(99L);
        subscriptionRepository.flush();
        assertThat(noticeService.notify(grant.getId(), today)).isFalse();
    }

    private MvcTestResult register(User who, String token, String platform) {
        return call(who, "/api/auth/devices", "{\"token\":\"" + token + "\",\"platform\":\"" + platform + "\"}");
    }

    private MvcTestResult call(User who, String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService
                        .createAccessToken(who.getPublicId(), who.getUserRole(), who.getUserStatus()).value())
                .exchange();
    }

    private static void assertCode(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    private User save(String email) {
        User saved = userRepository.save(User.localUser(email, "e", email.substring(0, 8)));
        subscriptionRepository.save(UserSubscription.createBasic(saved.getId()));
        return saved;
    }
}
