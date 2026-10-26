package com.harucut.terms.service;

import com.harucut.terms.entity.TermsAgreement;
import com.harucut.terms.repository.CurrentTermsVersionRepository;
import com.harucut.terms.repository.TermsAgreementRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "이 사용자가 현재 필수 약관에 모두 동의했는가"를 요청마다 답한다 (TermsConsentInterceptor).
 *
 * <p>활성 필수 약관 목록만 짧게(기본 10초) 캐시한다. 관리자가 약관을 바꾸면 TTL 안에 반영된다 —
 * 개정 직후 몇 초는 옛 기준으로 판정해도 문제가 없다. 사용자별 동의 상태는 캐시하지 않는다 —
 * 방금 동의한 사용자가 캐시 때문에 계속 막히면 안 된다.
 */
@Service
@Transactional(readOnly = true)
public class TermsConsentGuard {

    private final CurrentTermsVersionRepository currentTermsVersionRepository;
    private final TermsAgreementRepository termsAgreementRepository;
    private final Clock clock;
    private final Duration cacheTtl;

    private volatile Cached cached;

    public TermsConsentGuard(CurrentTermsVersionRepository currentTermsVersionRepository,
                             TermsAgreementRepository termsAgreementRepository,
                             Clock clock,
                             @Value("${terms.consent.required-cache-ttl:10s}") Duration cacheTtl) {
        this.currentTermsVersionRepository = currentTermsVersionRepository;
        this.termsAgreementRepository = termsAgreementRepository;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    /**
     * 동의가 빠졌거나 옛 버전이면 true. 활성 필수 약관이 하나도 없으면 항상 false 다 —
     * 약관을 아직 등록하지 않은 환경(운영 0건)에서 모든 사용자를 막지 않는다.
     *
     * <p>판정 규칙은 {@code TermsAgreementStatus.of} 와 같다: 현재 버전과 같은 버전에 동의(agreed=true)해야 한다.
     */
    public boolean isConsentMissing(String publicId) {
        Map<Long, Integer> required = requiredTerms();
        if (required.isEmpty()) {
            return false;
        }

        Map<Long, TermsAgreement> mine = termsAgreementRepository.findAllByUserPublicId(publicId).stream()
                .collect(Collectors.toMap(a -> a.getTerms().getId(), a -> a));

        return required.entrySet().stream().anyMatch(entry -> {
            TermsAgreement agreement = mine.get(entry.getKey());
            return agreement == null
                    || !agreement.isAgreed()
                    || agreement.getAgreedVersion() != entry.getValue();
        });
    }

    // terms_id → 현재 버전
    private Map<Long, Integer> requiredTerms() {
        Instant now = clock.instant();
        Cached current = cached;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.required();
        }
        Map<Long, Integer> loaded = currentTermsVersionRepository.findAllActive().stream()
                .filter(c -> c.getTerms().isRequired())
                .collect(Collectors.toUnmodifiableMap(
                        c -> c.getTerms().getId(),
                        c -> c.getTermsVersion().getVersion()));
        cached = new Cached(loaded, now.plus(cacheTtl));
        return loaded;
    }

    private record Cached(Map<Long, Integer> required, Instant expiresAt) {
    }
}
