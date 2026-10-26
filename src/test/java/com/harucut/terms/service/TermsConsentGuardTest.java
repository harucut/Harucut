package com.harucut.terms.service;

import com.harucut.terms.entity.CurrentTermsVersion;
import com.harucut.terms.entity.TermsAgreement;
import com.harucut.terms.repository.CurrentTermsVersionRepository;
import com.harucut.terms.repository.TermsAgreementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TermsConsentGuard — 필수 약관 동의 판정")
class TermsConsentGuardTest {

    private static final String PUBLIC_ID = "ConsentUser1";
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    @Mock
    private CurrentTermsVersionRepository currentTermsVersionRepository;

    @Mock
    private TermsAgreementRepository termsAgreementRepository;

    private MutableClock clock;
    private TermsConsentGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        guard = new TermsConsentGuard(currentTermsVersionRepository, termsAgreementRepository,
                clock, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("활성 필수 약관이 없으면 막지 않는다 — 약관 0건 환경")
    void noRequiredTerms() {
        List<CurrentTermsVersion> active = List.of(current(1L, 1, false));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);

        assertThat(guard.isConsentMissing(PUBLIC_ID)).isFalse();
        then(termsAgreementRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("현재 버전에 동의했으면 통과한다")
    void agreedCurrentVersion() {
        List<CurrentTermsVersion> active = List.of(current(1L, 2, true), current(2L, 1, false));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);
        List<TermsAgreement> mine = List.of(agreement(1L, 2, true));
        given(termsAgreementRepository.findAllByUserPublicId(PUBLIC_ID)).willReturn(mine);

        assertThat(guard.isConsentMissing(PUBLIC_ID)).isFalse();
    }

    @Test
    @DisplayName("필수 약관 동의 기록이 없으면 막는다")
    void notAgreed() {
        List<CurrentTermsVersion> active = List.of(current(1L, 1, true));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);
        given(termsAgreementRepository.findAllByUserPublicId(PUBLIC_ID)).willReturn(List.of());

        assertThat(guard.isConsentMissing(PUBLIC_ID)).isTrue();
    }

    @Test
    @DisplayName("옛 버전에 동의했으면 막는다 — 재동의 필요")
    void outdatedVersion() {
        List<CurrentTermsVersion> active = List.of(current(1L, 3, true));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);
        List<TermsAgreement> mine = List.of(agreement(1L, 2, true));
        given(termsAgreementRepository.findAllByUserPublicId(PUBLIC_ID)).willReturn(mine);

        assertThat(guard.isConsentMissing(PUBLIC_ID)).isTrue();
    }

    @Test
    @DisplayName("agreed=false 행이면 막는다")
    void declined() {
        List<CurrentTermsVersion> active = List.of(current(1L, 1, true));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);
        List<TermsAgreement> mine = List.of(agreement(1L, 1, false));
        given(termsAgreementRepository.findAllByUserPublicId(PUBLIC_ID)).willReturn(mine);

        assertThat(guard.isConsentMissing(PUBLIC_ID)).isTrue();
    }

    @Test
    @DisplayName("필수 약관 목록은 TTL 동안 캐시하고 지나면 다시 읽는다. 사용자 동의는 매번 읽는다")
    void cachesRequiredTermsOnly() {
        List<CurrentTermsVersion> active = List.of(current(1L, 1, true));
        given(currentTermsVersionRepository.findAllActive()).willReturn(active);
        List<TermsAgreement> mine = List.of(agreement(1L, 1, true));
        given(termsAgreementRepository.findAllByUserPublicId(PUBLIC_ID)).willReturn(mine);

        guard.isConsentMissing(PUBLIC_ID);
        clock.advance(Duration.ofSeconds(9));
        guard.isConsentMissing(PUBLIC_ID);
        then(currentTermsVersionRepository).should(times(1)).findAllActive();

        clock.advance(Duration.ofSeconds(2));
        guard.isConsentMissing(PUBLIC_ID);
        then(currentTermsVersionRepository).should(times(2)).findAllActive();
        then(termsAgreementRepository).should(times(3)).findAllByUserPublicId(PUBLIC_ID);
    }

    private static CurrentTermsVersion current(Long termsId, int version, boolean required) {
        CurrentTermsVersion current = mock(CurrentTermsVersion.class, Answers.RETURNS_DEEP_STUBS);
        given(current.getTerms().getId()).willReturn(termsId);
        given(current.getTerms().isRequired()).willReturn(required);
        given(current.getTermsVersion().getVersion()).willReturn(version);
        return current;
    }

    private static TermsAgreement agreement(Long termsId, int version, boolean agreed) {
        TermsAgreement agreement = mock(TermsAgreement.class, Answers.RETURNS_DEEP_STUBS);
        given(agreement.getTerms().getId()).willReturn(termsId);
        given(agreement.getAgreedVersion()).willReturn(version);
        given(agreement.isAgreed()).willReturn(agreed);
        return agreement;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
