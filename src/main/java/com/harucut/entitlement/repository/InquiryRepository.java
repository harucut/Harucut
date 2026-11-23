package com.harucut.entitlement.repository;

import com.harucut.entitlement.entity.Inquiry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    Optional<Inquiry> findByPublicId(String publicId);

    List<Inquiry> findAllByOrderByIdDesc(Pageable pageable);
}
