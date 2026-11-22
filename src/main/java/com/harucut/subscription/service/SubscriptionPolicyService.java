package com.harucut.subscription.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.policy.FrameSubscriptionPolicy;
import com.harucut.media.policy.MediaSubscriptionPolicy;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.policy.FrameLimit;
import com.harucut.subscription.policy.PlanPolicy;
import com.harucut.subscription.policy.Retention;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionPolicyService implements FrameSubscriptionPolicy, MediaSubscriptionPolicy {

    private final UserSubscriptionRepository userSubscriptionRepository;
    private final Clock clock;

    @Override
    public void assertFrameRetentionLimit(Long userId, int currentFrameCount) {
        FrameLimit limit = resolvePolicy(userId).frameRetentionLimit();

        if (!limit.allows(currentFrameCount)) {
            throw new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED);
        }
    }

    @Override
    public Integer resolveFrameRetentionCap(Long userId) {
        FrameLimit limit = resolvePolicy(userId).frameRetentionLimit();

        return limit.isUnlimited() ? null : limit.maxOrUnlimited();
    }

    @Override
    public LocalDateTime resolveHistoryCutoff(Long userId) {
        Retention retention = resolvePolicy(userId).historyRetention();

        return retention.cutoffFrom(LocalDateTime.now(clock));
    }

    @Override
    public boolean isHistoryAccessible(Long userId, LocalDateTime createdAt) {
        Retention retention = resolvePolicy(userId).historyRetention();

        return retention.isAccessible(createdAt, LocalDateTime.now(clock));
    }

    @Override
    public void assertHistoryAccessible(Long userId, LocalDateTime createdAt) {
        if (!isHistoryAccessible(userId, createdAt)) {
            throw new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED);
        }
    }

    private PlanPolicy resolvePolicy(Long userId) {
        return userSubscriptionRepository.findByUserId(userId)
                .map(subscription -> subscription.effectiveTier(LocalDateTime.now(clock)))
                .orElse(PlanTier.BASIC)
                .getPolicy();
    }
}
