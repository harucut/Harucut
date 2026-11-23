package com.harucut.subscription.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.subscription.dto.SubscriptionUsageResponse;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

// 요금제 한도 임시 통일(전부 무제한) 상태의 기대값이다.
// 정책이 다시 갈라지면 git 이력의 이전 버전이 요금제별 시나리오 목록이다 —
// 특히 Limited 한도의 -1 아닌 응답과 잔여 0 클램프(강등 시나리오)는 그때 되살려야 한다
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionUsageService")
class SubscriptionUsageServiceTest {

    private static final String PUBLIC_ID = "AbCdEf12Gh";
    private static final Long USER_ID = 1L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0);

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private FrameRepository frameRepository;

    private SubscriptionUsageService usageService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        usageService = new SubscriptionUsageService(
                userRepository, userSubscriptionRepository, frameRepository, fixedClock);
    }

    @Test
    @DisplayName("모든 요금제 — 한도·잔여 -1(무제한 규약), 사용량은 실제 개수 그대로다")
    void allTiersUnlimited() {
        for (PlanTier tier : PlanTier.values()) {
            givenTier(tier);
            given(frameRepository.countPersonalByUserId(USER_ID)).willReturn(10L);

            SubscriptionUsageResponse response = usageService.getUsage(PUBLIC_ID);

            assertThat(response.planTier()).isEqualTo(tier);
            assertThat(response.frameRetentionLimit()).isEqualTo(-1);
            assertThat(response.frameRetentionUsedCount()).isEqualTo(10);
            assertThat(response.frameRetentionRemainingCount()).isEqualTo(-1);
            assertThat(response.frameRetentionUnlimited()).isTrue();
        }
    }

    @Test
    @DisplayName("공백기 — 주기가 끝난 PLUS는 planTier가 BASIC으로 응답된다")
    void gapPeriodShowsBasicTier() {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        subscription.activatePaid(PlanTier.PLUS,
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0));
        givenUser();
        given(userSubscriptionRepository.findByUserId(USER_ID)).willReturn(Optional.of(subscription));
        given(frameRepository.countPersonalByUserId(USER_ID)).willReturn(2L);

        SubscriptionUsageResponse response = usageService.getUsage(PUBLIC_ID);

        assertThat(response.planTier()).isEqualTo(PlanTier.BASIC);
        assertThat(response.frameRetentionLimit()).isEqualTo(-1);
    }

    @Test
    @DisplayName("구독 행이 없으면 예외가 아니라 BASIC으로 응답한다")
    void noSubscriptionFallsBackToBasic() {
        givenUser();
        given(userSubscriptionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(frameRepository.countPersonalByUserId(USER_ID)).willReturn(0L);

        SubscriptionUsageResponse response = usageService.getUsage(PUBLIC_ID);

        assertThat(response.planTier()).isEqualTo(PlanTier.BASIC);
        assertThat(response.frameRetentionLimit()).isEqualTo(-1);
    }

    @Test
    @DisplayName("사용자가 없으면 GEN-031을 던진다")
    void userNotFound() {
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> usageService.getUsage(PUBLIC_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    private void givenUser() {
        User user = User.localUser("user@harucut.com", "encoded", "하루컷");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
    }

    private void givenTier(PlanTier tier) {
        UserSubscription subscription = UserSubscription.createBasic(USER_ID);
        if (tier != PlanTier.BASIC) {
            subscription.activatePaid(tier,
                    LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0));
        }
        givenUser();
        given(userSubscriptionRepository.findByUserId(USER_ID)).willReturn(Optional.of(subscription));
    }
}
