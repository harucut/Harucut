package com.harucut.user.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.common.utils.PublicIds;
import com.harucut.user.enums.Provider;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

// @DynamicUpdate: 프로필·비밀번호를 고쳐 저장할 때 전체 열을 쓰면, 그사이 커밋된 정지(BLOCKED)를 읽었던 낡은 값으로 되돌린다 — 바뀐 열만 쓴다
@DynamicUpdate
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uk_users_provider_email", columnNames = {"provider", "email"}),
                @UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    private static final String DEFAULT_PROFILE_IMAGE = "resources/defaults/userDefaultImage.png";
    public static final int DELETION_GRACE_DAYS = 7;
    private static final String DELETED_USERNAME = "탈퇴한 사용자";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "public_id", nullable = false, length = 12)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 20)
    private UserRole userRole;

    @Column(nullable = false)
    private String email;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 20)
    private String username;

    @Column(name = "profile_image_url", nullable = false, length = 1024)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 20)
    private UserStatus userStatus;

    @Column(name = "delete_requested_at")
    private LocalDateTime deleteRequestedAt;

    private User(Provider provider, String providerId, String email,
                 String password, String username) {
        this.publicId = PublicIds.generate();
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.password = password;
        this.username = username;
        this.userRole = UserRole.ROLE_USER;
        this.userStatus = UserStatus.ACTIVE;
        this.profileImageUrl = DEFAULT_PROFILE_IMAGE;
    }

    public static User localUser(String email, String encodedPassword, String username) {
        return new User(Provider.HARUCUT, null, email, encodedPassword, username);
    }

    public static User socialUser(Provider provider, String providerId, String email, String username) {
        return new User(provider, providerId, email, null, username);
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void changeUsername(String username) {
        this.username = username;
    }

    public void changeProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public void deleteRequested(LocalDateTime now) {
        this.userStatus = UserStatus.DELETED_REQUESTED;
        this.deleteRequestedAt = now;
    }

    public void reActivate() {
        this.userStatus = UserStatus.ACTIVE;
        this.deleteRequestedAt = null;
    }

    public void block() {
        this.userStatus = UserStatus.BLOCKED;
    }

    public void unblock() {
        this.userStatus = UserStatus.ACTIVE;
    }

    public void delete() {
        this.userStatus = UserStatus.DELETED;
        this.email = "deleted_" + id + "@harucut.local";
        this.username = DELETED_USERNAME;
        this.password = null;
        this.providerId = null;
        this.profileImageUrl = DEFAULT_PROFILE_IMAGE;
    }
}
