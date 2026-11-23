package com.harucut.terms.repository;

import com.harucut.config.JpaAuditingConfig;
import com.harucut.support.FixedClockConfig;
import com.harucut.terms.entity.Terms;
import com.harucut.terms.entity.TermsAgreement;
import com.harucut.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.autoconfigure.json.AutoConfigureJson;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureJson   // 다른 엔티티의 JSON 컨버터가 ObjectMapper 를 요구한다
@Import({JpaAuditingConfig.class, FixedClockConfig.class})
@ActiveProfiles("test")
@DisplayName("TermsAgreementRepository")
class TermsAgreementRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @Test
    @DisplayName("publicId 로 그 사용자의 동의 행만 읽는다 — 동의 강제 검사가 요청마다 쓰는 쿼리")
    void findsOnlyMyAgreementsByPublicId() {
        User me = em.persist(User.localUser("me@harucut.com", "encoded", "나"));
        User other = em.persist(User.localUser("other@harucut.com", "encoded", "남"));
        Terms tos = em.persist(Terms.create("tos", "이용약관", true));
        Terms privacy = em.persist(Terms.create("privacy", "개인정보", true));
        em.persist(TermsAgreement.of(me.getId(), tos, 1, true));
        em.persist(TermsAgreement.of(me.getId(), privacy, 2, true));
        em.persist(TermsAgreement.of(other.getId(), tos, 1, true));
        em.flush();
        em.clear();

        assertThat(termsAgreementRepository.findAllByUserPublicId(me.getPublicId()))
                .extracting(a -> a.getTerms().getCode())
                .containsExactlyInAnyOrder("tos", "privacy");
        assertThat(termsAgreementRepository.findAllByUserPublicId("NoSuchUser00")).isEmpty();
    }
}
