package com.harucut.entitlement.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.entitlement.enums.InquiryStatus;
import com.harucut.entitlement.enums.OrganizerType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

// 행사·기업 문의 (ADR-0008 §6-1). 로그인 없이 받는다
@Entity
@Table(name = "inquiry", indexes = @Index(name = "idx_inquiry_status", columnList = "status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 12)
    private String publicId;

    @Column(name = "contact_name", nullable = false, length = 50)
    private String contactName;

    @Column(nullable = false, length = 120)
    private String email;

    @Column(length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "organizer_type", nullable = false)
    private OrganizerType organizerType;

    // BYOD·BOOTH 의 쉼표 목록
    @Column(nullable = false, length = 30)
    private String channels;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "expected_count")
    private Integer expectedCount;

    @Column(nullable = false, length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InquiryStatus status;

    @Column(name = "assignee_note", length = 1000)
    private String assigneeNote;

    public Inquiry(String contactName, String email, String phone, OrganizerType organizerType, String channels,
                   LocalDate expectedDate, Integer expectedCount, String message) {
        this.publicId = PublicIds.generate();
        this.contactName = contactName;
        this.email = email;
        this.phone = phone;
        this.organizerType = organizerType;
        this.channels = channels;
        this.expectedDate = expectedDate;
        this.expectedCount = expectedCount;
        this.message = message;
        this.status = InquiryStatus.NEW;
    }

    public void update(InquiryStatus status, String assigneeNote) {
        if (status != null) {
            this.status = status;
        }
        if (assigneeNote != null) {
            this.assigneeNote = assigneeNote;
        }
    }
}
