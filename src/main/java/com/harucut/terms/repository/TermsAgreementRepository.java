package com.harucut.terms.repository;

import com.harucut.terms.entity.TermsAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, Long> {

    // 내 동의 상태: 약관당 1행이라 행 수 = 이 사용자가 건드려본 약관 수
    @Query("""
            SELECT a FROM TermsAgreement a
            JOIN FETCH a.terms t
            WHERE a.userId = :userId
            """)
    List<TermsAgreement> findAllByUserId(@Param("userId") Long userId);

    // 동의 강제 검사용: 요청마다 불린다. 인증 주체에는 publicId 만 있어서 users 와 이어 한 번에 읽는다.
    // terms_agreement.user_id 는 FK 가 아닌 스칼라라 연관 대신 세타 조인이다
    @Query("""
            SELECT a FROM TermsAgreement a, User u
            WHERE u.publicId = :publicId AND a.userId = u.id
            """)
    List<TermsAgreement> findAllByUserPublicId(@Param("publicId") String publicId);

    // 동의/철회: 이 행이 있으면 update, 없으면 insert
    Optional<TermsAgreement> findByUserIdAndTermsId(Long userId, Long termsId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TermsAgreement a WHERE a.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
