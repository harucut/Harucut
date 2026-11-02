package com.harucut.event.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.event.converter.OperationPermissionsConverter;
import com.harucut.event.converter.PhotoPermissionsConverter;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

// 한 사용자가 한 행사의 사진에 대해 갖는 권한. 공간 멤버가 아닐 수 있다(행사 고객) — R5 3절
@Entity
@Table(name = "event_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_grant", columnNames = {"event_id", "user_id"}),
        indexes = @Index(name = "idx_event_grant_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventGrant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_grant_id")
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Convert(converter = PhotoPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<PhotoPermission> permissions = EnumSet.noneOf(PhotoPermission.class);

    // 운영 권한(ADR-0009 §3) — 공간 역할이 OPERATOR 일 때만 효력이 있다. 행사 고객은 비어 있다
    @Convert(converter = OperationPermissionsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<OperationPermission> operations = EnumSet.noneOf(OperationPermission.class);

    @Column(name = "granted_by_user_id")
    private Long grantedByUserId;

    // 이 시각 이후에 제출된 사진만 — NULL 이면 제한 없음. 새 수신자가 과거 사진까지 저절로 보지 않게(R5 L347)
    @Column(name = "photos_from")
    private LocalDateTime photosFrom;

    public EventGrant(Long eventId, Long userId, Set<PhotoPermission> permissions, Long grantedByUserId) {
        this.eventId = eventId;
        this.userId = userId;
        this.permissions = permissions.isEmpty() ? EnumSet.noneOf(PhotoPermission.class) : EnumSet.copyOf(permissions);
        this.grantedByUserId = grantedByUserId;
    }

    public void replaceOperations(Set<OperationPermission> operations) {
        this.operations = operations.isEmpty() ? EnumSet.noneOf(OperationPermission.class) : EnumSet.copyOf(operations);
    }

    public boolean isEmpty() {
        return permissions.isEmpty() && operations.isEmpty();
    }

    public boolean allows(PhotoPermission permission) {
        return permissions.contains(permission);
    }

    public void replace(Set<PhotoPermission> permissions) {
        this.permissions = permissions.isEmpty() ? EnumSet.noneOf(PhotoPermission.class) : EnumSet.copyOf(permissions);
    }

    /** 권한 수정 — 처음 사진 권한을 받으면 그 시각 이후 사진만. includePast 면 제한을 푼다. 아니면 이미 정한 범위를 바꾸지 않는다 */
    public void replace(Set<PhotoPermission> permissions, boolean includePast, LocalDateTime now) {
        LocalDateTime from = photosFromAfter(includePast, now);
        replace(permissions);
        this.photosFrom = from;
    }

    /** 초대 수락 — 권한은 합친다(이미 받은 권한을 줄이지 않는다). 범위는 권한 수정과 같다 */
    public void merge(Set<PhotoPermission> more, boolean includePast, LocalDateTime now) {
        LocalDateTime from = photosFromAfter(includePast, now);
        EnumSet<PhotoPermission> merged = EnumSet.noneOf(PhotoPermission.class);
        merged.addAll(permissions);
        merged.addAll(more);
        this.permissions = merged;
        this.photosFrom = from;
    }

    private LocalDateTime photosFromAfter(boolean includePast, LocalDateTime now) {
        if (includePast) {
            return null;
        }
        return permissions.isEmpty() ? now : photosFrom;
    }
}
