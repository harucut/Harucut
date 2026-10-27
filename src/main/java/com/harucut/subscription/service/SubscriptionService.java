package com.harucut.subscription.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final Clock clock;

    public SubscriptionResponse getMySubscription(String publicId) {
        UserSubscription subscription = getSubscription(publicId);
        PlanTier effectiveTier = subscription.effectiveTier(LocalDateTime.now(clock));
        return SubscriptionResponse.of(subscription, effectiveTier, publicId);
    }

    @Transactional
    public void cancelAutoRenew(String publicId) {
        UserSubscription subscription = getSubscription(publicId);

        // 스토어 구독은 그 스토어의 구독 관리에서만 끊을 수 있다
        SubscriptionSource source = subscription.activeSourceAt(LocalDateTime.now(clock));
        if (source == SubscriptionSource.APP_STORE || source == SubscriptionSource.PLAY_STORE) {
            throw new BusinessException(SubscriptionErrorCode.SUBSCRIBED_ELSEWHERE, "스토어 구독은 스토어에서 해지합니다.",
                    Map.of("source", source));
        }
        if (subscription.getSubscriptionStatus() == SubscriptionStatus.CANCELED) {
            throw new BusinessException(SubscriptionErrorCode.ALREADY_CANCELED);
        }
        if (!subscription.isAutoRenew()) {
            throw new BusinessException(SubscriptionErrorCode.NO_AUTO_RENEWAL_TO_CANCEL);
        }

        subscription.cancelAutoRenew();
    }

    private UserSubscription getSubscription(String publicId) {
        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND, "User not found."));
        return userSubscriptionRepository.findByUserId(user.getId())
                .orElseThrow(() -> new BusinessException(SubscriptionErrorCode.NO_ACTIVE_SUBSCRIPTION));
    }
}
