package com.harucut.subscription.store.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.coupon.service.GrantActivationService;
import com.harucut.payment.exception.PaymentErrorCode;
import com.harucut.payment.webhook.WebhookReceiptRepository;
import com.harucut.subscription.dto.SubscriptionResponse;
import com.harucut.subscription.entity.UserSubscription;
import com.harucut.subscription.enums.PlanTier;
import com.harucut.subscription.enums.SubscriptionSource;
import com.harucut.subscription.enums.SubscriptionStatus;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.subscription.repository.UserSubscriptionRepository;
import com.harucut.subscription.store.StoreAccountTokens;
import com.harucut.subscription.store.StorePlatform;
import com.harucut.subscription.store.config.StoreProperties;
import com.harucut.subscription.store.dto.StorePurchaseDtos;
import com.harucut.subscription.store.entity.StorePurchase;
import com.harucut.subscription.store.repository.StorePurchaseRepository;
import com.harucut.subscription.store.verifier.AppStoreNotification;
import com.harucut.subscription.store.verifier.AppStoreVerifier;
import com.harucut.subscription.store.verifier.PlayStoreVerifier;
import com.harucut.subscription.store.verifier.StoreVerificationException;
import com.harucut.subscription.store.verifier.VerifiedPurchase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * 스토어 구독 (ADR-0011 §2) — 앱이 보낸 구매 증빙을 스토어 검증기로 확인해 이 계정에 묶고, 구독을 그 구매의 상태로 맞춘다.
 * 이후 갱신·자동갱신 끔·결제 재시도·만료·환불은 스토어 알림이 같은 경로로 반영한다.
 *
 * <p>규칙: 한 구매는 한 계정(다른 계정에 묶였거나 다른 계정 표시가 실렸으면 SUBS-008), 유료 출처는 하나(웹·다른 스토어로 구독 중이면
 * SUBS-009 + data.source — 쿠폰 무료 부여는 스토어 구매가 대신한다). 잠금 순서는 늘 구독 행 → 스토어 구매 행.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class StoreSubscriptionService {

    private final UserSubscriptionRepository subscriptionRepository;
    private final StorePurchaseRepository purchaseRepository;
    private final AppStoreVerifier appStoreVerifier;
    private final PlayStoreVerifier playStoreVerifier;
    private final StoreProperties properties;
    private final WebhookReceiptRepository receiptRepository;
    private final GrantActivationService grantActivationService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SubscriptionResponse purchase(String userPublicId, StorePurchaseDtos.PurchaseRequest request) {
        // 검증은 DB 를 보지 않는다 — 첫 문장이 구독 행 잠금이어야 그 뒤 읽기가 먼저 끝난 요청·알림을 본다
        VerifiedPurchase verified = verify(request.platform(), request.productId(), request.proof());
        PlanTier tier = tierOf(verified.productId());
        LocalDateTime now = now();
        if (isOver(verified, now)) {
            throw new BusinessException(SubscriptionErrorCode.STORE_VERIFICATION_FAILED, "만료·취소된 구매 — 등록할 수 없다");
        }
        UserSubscription subscription = lockSubscription(userPublicId);
        requireNoOtherSource(subscription, request.platform(), now);
        StorePurchase purchase = bind(subscription.getUserId(), userPublicId, verified, tier, now);
        if (!purchase.grantsAt(now)) {
            // 이미 환불·만료로 기록된 구매의 옛 증빙 — 저장된 상태가 이긴다
            throw new BusinessException(SubscriptionErrorCode.STORE_VERIFICATION_FAILED, "환불·만료로 기록된 구매의 옛 증빙");
        }
        sync(subscription, subscription.getUserId(), request.platform(), now);
        return SubscriptionResponse.of(subscription, subscription.effectiveTier(now), userPublicId);
    }

    /**
     * 복원 — 검증되고 아직 유효한 구매를 이 계정에 묶고 그중 가장 늦게 끝나는 것으로 구독을 맞춘다. 검증 실패·만료·모르는 상품은 건너뛴다.
     * 유효한 구매가 모두 다른 계정 것이면 SUBS-008, 하나도 없으면 지금 구독을 그대로 돌려준다(복원할 것 없음 — planTier 로 안다).
     */
    public SubscriptionResponse restore(String userPublicId, StorePurchaseDtos.RestoreRequest request) {
        LocalDateTime now = now();
        UserSubscription subscription = lockSubscription(userPublicId);
        int restored = 0;
        int ownedByOthers = 0;
        boolean otherSourceChecked = false;
        for (StorePurchaseDtos.RestoreItem item : request.items()) {
            Optional<VerifiedPurchase> verified = tryVerify(request.platform(), item);
            Optional<PlanTier> tier = verified.map(VerifiedPurchase::productId).map(properties.products()::get);
            if (verified.isEmpty() || tier.isEmpty() || isOver(verified.get(), now)) {
                continue;
            }
            if (!otherSourceChecked) {
                requireNoOtherSource(subscription, request.platform(), now);
                otherSourceChecked = true;
            }
            try {
                if (bind(subscription.getUserId(), userPublicId, verified.get(), tier.get(), now).grantsAt(now)) {
                    restored++;
                }
            } catch (BusinessException e) {
                if (e.getErrorCode() != SubscriptionErrorCode.STORE_PURCHASE_OWNED_BY_OTHER) {
                    throw e;
                }
                ownedByOthers++;
            }
        }
        if (restored == 0 && ownedByOthers > 0) {
            throw new BusinessException(SubscriptionErrorCode.STORE_PURCHASE_OWNED_BY_OTHER, "복원할 구매가 모두 다른 계정 것");
        }
        if (restored > 0) {
            sync(subscription, subscription.getUserId(), request.platform(), now);
        }
        return SubscriptionResponse.of(subscription, subscription.effectiveTier(now), userPublicId);
    }

    /** App Store Server Notifications v2 — 같은 알림은 한 번만. 모르는 구매(앱이 아직 등록하지 않음)는 건너뛴다 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void appStoreNotification(String signedPayload) {
        AppStoreNotification notification;
        try {
            notification = appStoreVerifier.verifyNotification(signedPayload);
        } catch (StoreVerificationException e) {
            log.warn("[스토어] 앱스토어 알림 검증 실패: {}", e.getMessage());
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        if (notification.transaction() == null) {
            record("APP_STORE", notification.notificationId(), notification.type());
            return;
        }
        applyFromStore(notification.transaction(), notification.notificationId(), notification.type());
    }

    /**
     * Google Play RTDN(Pub/Sub 푸시) — 알림은 「다시 조회하라」는 신호일 뿐이고 상태는 Play API 로 다시 읽는다.
     * 푸시 URL 의 token 이 설정과 같아야 한다(상수 시간 비교).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void playNotification(String token, String purchaseToken, String productId) {
        String expected = properties.google().pushToken();
        if (expected.isBlank() || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        if (purchaseToken == null) {
            return;   // 테스트 알림·구독이 아닌 알림
        }
        Optional<StorePurchase> known = purchaseRepository.findByPlatformAndStoreKeyHash(StorePlatform.PLAY_STORE, StorePurchase.hash(purchaseToken));
        if (known.isEmpty()) {
            return;
        }
        VerifiedPurchase verified;
        try {
            verified = playStoreVerifier.verify(productId != null ? productId : known.get().getProductId(), purchaseToken);
        } catch (StoreVerificationException e) {
            log.warn("[스토어] 플레이 재조회 실패 — 다음 알림·재검증에서 다시 본다: {}", e.getMessage());
            return;
        }
        applyFromStore(verified, null, null);
    }

    /**
     * 스토어가 알려 준 최신 상태를 구매와(그 구매가 구독의 출처면) 구독에 반영한다. 구매의 주인은 바뀌지 않으므로 트랜잭션 밖에서 읽고,
     * 반영 트랜잭션은 구독 행 잠금으로 시작한다(그 뒤 읽기가 먼저 끝난 등록·알림을 본다). 알림 수신 기록도 같은 트랜잭션 — 실패하면 재전송이 다시 처리한다
     */
    private void applyFromStore(VerifiedPurchase verified, String notificationId, String type) {
        Optional<StorePurchase> known = purchaseRepository.findByPlatformAndStoreKeyHash(verified.platform(), StorePurchase.hash(verified.storeKey()));
        if (known.isEmpty()) {
            log.info("[스토어] 등록되지 않은 구매의 알림 — 앱이 등록·복원할 때 반영된다");
            if (notificationId != null) {
                record(verified.platform().name(), notificationId, type);
            }
            return;
        }
        Long userId = known.get().getUserId();
        transaction.executeWithoutResult(status -> apply(verified, userId, notificationId, type));
    }

    private void record(String provider, String notificationId, String type) {
        transaction.executeWithoutResult(status -> receiptRepository.recordOnce(now(), provider, notificationId, type));
    }

    private void apply(VerifiedPurchase verified, Long userId, String notificationId, String type) {
        LocalDateTime now = now();
        UserSubscription subscription = subscriptionRepository.lockByUserId(userId)
                .orElseThrow(() -> new BusinessException(SubscriptionErrorCode.NO_ACTIVE_SUBSCRIPTION));
        if (notificationId != null && receiptRepository.recordOnce(now, verified.platform().name(), notificationId, type) == 0) {
            return;   // 이미 처리한 알림
        }
        StorePurchase purchase = purchaseRepository.lockByKey(verified.platform(), StorePurchase.hash(verified.storeKey())).orElseThrow();
        // 늦게 도착한 옛 알림이 만료를 되돌리지 않게 — 환불이 아니면 기한은 앞으로만
        VerifiedPurchase merged = !verified.revoked() && verified.expiresAt().isBefore(purchase.getExpiresAt())
                ? new VerifiedPurchase(verified.platform(), verified.storeKey(), purchase.getProductId(), purchase.getExpiresAt(),
                verified.autoRenew(), false, verified.billingRetry(), verified.environment(), verified.accountToken())
                : verified;
        // 저장된 상품·기한을 남겼으면 등급도 저장값 — 알림 상품의 등급과 섞지 않는다(모르는 상품도 저장값)
        PlanTier tier = merged == verified ? properties.products().getOrDefault(verified.productId(), purchase.getPlanTier())
                : purchase.getPlanTier();
        purchase.apply(merged, tier, now);
        // 이 스토어가 구독의 출처면 늘 맞추고(연장·강등), 지금 무료·쿠폰이면 유효한 구매로 다시 올린다. 웹·다른 스토어로 구독 중이면 구매만 갱신
        SubscriptionSource active = activeSource(subscription, now);
        boolean drivenByThis = subscription.getSource() == verified.platform().source();
        boolean free = active == null || active == SubscriptionSource.COUPON;
        if (drivenByThis || free && purchase.grantsAt(now)) {
            sync(subscription, purchase.getUserId(), verified.platform(), now);
        }
    }

    /**
     * 이 계정에 묶는다 — 이미 다른 계정 것이거나 다른 계정 표시가 실렸으면 SUBS-008.
     * 앱이 보낸 증빙은 서명은 맞아도 옛 스냅샷일 수 있다(환불·자동갱신 끔은 거래 JWS 에 늦게 실리거나 안 실린다). 그래서 이미 있는 구매는
     * 저장값보다 늦게 끝나는(새 갱신의) 증빙일 때만 바꾼다 — 알림이 반영한 환불(REVOKED)·자동갱신 끔을 옛 증빙이 되돌리지 못한다
     */
    private StorePurchase bind(Long userId, String userPublicId, VerifiedPurchase verified, PlanTier tier, LocalDateTime now) {
        if (verified.accountToken() != null && !verified.accountToken().equalsIgnoreCase(StoreAccountTokens.of(userPublicId))) {
            throw new BusinessException(SubscriptionErrorCode.STORE_PURCHASE_OWNED_BY_OTHER, "다른 계정 표시가 실린 구매");
        }
        String hash = StorePurchase.hash(verified.storeKey());
        // 넣어 보고(없는 키를 잠금 읽기하면 빈 구간 gap lock 끼리 교착한다) 그 행을 잠금 읽기로 다시 본다 — 먼저 묶은 계정이 이긴다
        int inserted = purchaseRepository.insertIfAbsent(userId, verified.platform().name(), verified.storeKey(), hash,
                verified.productId(), tier.name(), verified.statusAt(now).name(), verified.autoRenew(), verified.expiresAt(),
                verified.environment().name(), now);
        StorePurchase purchase = purchaseRepository.lockByKey(verified.platform(), hash).orElseThrow();
        if (!purchase.getUserId().equals(userId)) {
            throw new BusinessException(SubscriptionErrorCode.STORE_PURCHASE_OWNED_BY_OTHER, "다른 계정에 묶인 구매");
        }
        if (inserted == 1 || verified.expiresAt().isAfter(purchase.getExpiresAt())) {
            purchase.apply(verified, tier, now);
        }
        return purchase;
    }

    // 이 플랫폼에서 이 계정이 가진 구매 중 지금 등급을 주는 것(가장 늦게 끝나는 것)으로 구독을 맞춘다. 없으면 이 플랫폼이 출처였을 때만 강등
    private void sync(UserSubscription subscription, Long userId, StorePlatform platform, LocalDateTime now) {
        Optional<StorePurchase> best = purchaseRepository.findAllByUserIdAndPlatform(userId, platform).stream()
                .filter(purchase -> purchase.grantsAt(now))
                .max(Comparator.comparing(StorePurchase::getExpiresAt));
        if (best.isPresent()) {
            StorePurchase purchase = best.get();
            SubscriptionStatus status = switch (purchase.getStatus()) {
                case CANCELED -> SubscriptionStatus.CANCELED;
                case PAST_DUE -> SubscriptionStatus.PAST_DUE;
                default -> SubscriptionStatus.ACTIVE;
            };
            subscription.activateStore(platform.source(), purchase.getPlanTier(), status, now, purchase.getExpiresAt(), purchase.isAutoRenew());
        } else if (subscription.getSource() == platform.source()) {
            // 예약 쿠폰이 있으면 강등 대신 이어 받는다 — 만료 배치(SubscriptionExpirationService)와 같은 규칙
            if (subscription.getReservedUserCouponId() != null) {
                grantActivationService.activate(subscription, now);
            } else {
                subscription.expireToFree();
            }
        }
    }

    // 웹·다른 스토어로 지금 유료 등급을 받고 있으면 SUBS-009 — 쿠폰 무료 부여는 스토어 구매가 대신한다
    private static void requireNoOtherSource(UserSubscription subscription, StorePlatform platform, LocalDateTime now) {
        SubscriptionSource active = activeSource(subscription, now);
        if (active != null && active != SubscriptionSource.COUPON && active != platform.source()) {
            throw new BusinessException(SubscriptionErrorCode.SUBSCRIBED_ELSEWHERE, "다른 출처로 구독 중", Map.of("source", active));
        }
    }

    // 지금 유료 등급을 주는 출처 — 출처가 비어 있는 옛 유료 행은 웹 결제다(V24 이전)
    private static SubscriptionSource activeSource(UserSubscription subscription, LocalDateTime now) {
        if (subscription.effectiveTier(now) == PlanTier.BASIC) {
            return null;
        }
        return subscription.getSource() != null ? subscription.getSource() : SubscriptionSource.WEB;
    }

    private VerifiedPurchase verify(StorePlatform platform, String productId, String proof) {
        try {
            VerifiedPurchase verified = platform == StorePlatform.APP_STORE
                    ? appStoreVerifier.verifyTransaction(proof)
                    : playStoreVerifier.verify(productId, proof);
            if (!verified.productId().equals(productId)) {
                throw new StoreVerificationException("요청 상품과 검증된 상품이 다르다");
            }
            return verified;
        } catch (StoreVerificationException e) {
            log.warn("[스토어] {} 구매 검증 실패: {}", platform, e.getMessage());
            throw new BusinessException(SubscriptionErrorCode.STORE_VERIFICATION_FAILED);
        }
    }

    private Optional<VerifiedPurchase> tryVerify(StorePlatform platform, StorePurchaseDtos.RestoreItem item) {
        try {
            return Optional.of(verify(platform, item.productId(), item.proof()));
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    private PlanTier tierOf(String productId) {
        PlanTier tier = properties.products().get(productId);
        if (tier == null || tier == PlanTier.BASIC) {
            throw new BusinessException(SubscriptionErrorCode.UNKNOWN_STORE_PRODUCT, "모르는 스토어 상품: " + productId);
        }
        return tier;
    }

    private static boolean isOver(VerifiedPurchase verified, LocalDateTime now) {
        return verified.revoked() || !now.isBefore(verified.expiresAt());
    }

    private UserSubscription lockSubscription(String userPublicId) {
        return subscriptionRepository.lockByUserPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(SubscriptionErrorCode.NO_ACTIVE_SUBSCRIPTION));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
