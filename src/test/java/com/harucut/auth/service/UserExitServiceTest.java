package com.harucut.auth.service;

import com.harucut.workspace.service.WorkspaceService;
import org.mockito.InOrder;
import com.harucut.auth.exception.AuthErrorCode;
import com.harucut.auth.oauth2.unlink.OAuth2UnlinkService;
import com.harucut.common.exception.BusinessException;
import com.harucut.support.UserFixtures;
import com.harucut.user.entity.User;
import com.harucut.user.enums.Provider;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;


import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserExitService")
class UserExitServiceTest {

    private static final String PUBLIC_ID = "user-pub-001";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private UserDeletionHandler firstHandler;

    @Mock
    private UserDeletionHandler secondHandler;

    @Mock
    private OAuth2UnlinkService unlinkService;

    @Mock
    private com.harucut.storage.service.S3Deleter s3Deleter;

    @Mock
    private WorkspaceService workspaceService;

    private UserExitService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new UserExitService(userRepository, refreshTokenService, clock,
                List.of(firstHandler, secondHandler), List.of(unlinkService), s3Deleter, workspaceService);
    }

    @Nested
    @DisplayName("탈퇴 요청")
    class RequestExit {

        @Test
        @DisplayName("상태가 DELETED_REQUESTED가 되고 요청 시각이 기록된다")
        void marksDeleteRequested() {
            User user = givenActiveUserLocked();

            service.requestExit(PUBLIC_ID);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.DELETED_REQUESTED);
            assertThat(user.getDeleteRequestedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("조직 공간의 마지막 OWNER 면 MEMBER-004 — 탈퇴 상태로 넘어가지 않는다(R5 L561)")
        void lastOwnerCannotExit() {
            User user = givenActiveUserLocked();
            org.mockito.BDDMockito.willThrow(new com.harucut.common.exception.BusinessException(com.harucut.workspace.exception.MemberErrorCode.LAST_OWNER))
                    .given(workspaceService).requireNotLastOwner(user.getId());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requestExit(PUBLIC_ID))
                    .isInstanceOf(com.harucut.common.exception.BusinessException.class);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.ACTIVE);
            then(refreshTokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("정지 계정은 탈퇴 요청으로 정지를 벗어나지 못한다 — 살아 있는 access 토큰이어도 AUTH-042")
        void blockedCannotRequestExit() {
            User user = givenActiveUserLocked();
            user.block();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requestExit(PUBLIC_ID))
                    .isInstanceOf(com.harucut.common.exception.BusinessException.class)
                    .extracting("errorCode").isEqualTo(com.harucut.auth.exception.AuthErrorCode.ACCOUNT_BLOCKED);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.BLOCKED);
            then(refreshTokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("refresh 토큰이 삭제된다")
        void revokesRefreshToken() {
            givenActiveUserLocked();

            service.requestExit(PUBLIC_ID);

            then(refreshTokenService).should().revokeAll(PUBLIC_ID);
        }

        @Test
        @DisplayName("없는 사용자는 AUTH-020이고 토큰을 건드리지 않는다")
        void unknownUser() {
            given(userRepository.lockByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestExit(PUBLIC_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AuthErrorCode.USER_NOT_FOUND);

            then(refreshTokenService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("복구")
    class ReActivate {

        @Test
        @DisplayName("DELETED_REQUESTED면 ACTIVE로 돌아오고 요청 시각이 비워진다")
        void restoresToActive() {
            User user = givenDeleteRequestedUser();

            service.reActivate(PUBLIC_ID);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeleteRequestedAt()).isNull();
        }

        @Test
        @DisplayName("복구 시에도 refresh 토큰을 지워 재로그인을 강제한다")
        void revokesRefreshToken() {
            givenDeleteRequestedUser();

            service.reActivate(PUBLIC_ID);

            then(refreshTokenService).should().revokeAll(PUBLIC_ID);
        }

        @Test
        @DisplayName("DELETED_REQUESTED가 아니면 AUTH-007이고 상태가 그대로다")
        void notDeletionTarget() {
            User user = givenActiveUser();

            assertThatThrownBy(() -> service.reActivate(PUBLIC_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AuthErrorCode.NOT_DELETION_TARGET);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.ACTIVE);
            then(refreshTokenService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("하드 삭제 (배치가 부른다)")
    class Exit {

        private static final Long USER_ID = 7L;

        @Test
        @DisplayName("없는 사용자는 AUTH-020이고 핸들러가 돌지 않는다")
        void unknownUser() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.exit(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AuthErrorCode.USER_NOT_FOUND);

            then(firstHandler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("DELETED_REQUESTED가 아니면 AUTH-007이고 핸들러가 돌지 않는다")
        void notDeletionTarget() {
            User user = UserFixtures.localUser("exit@harucut.com", "encoded");
            ReflectionTestUtils.setField(user, "id", USER_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.exit(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AuthErrorCode.NOT_DELETION_TARGET);

            then(firstHandler).shouldHaveNoInteractions();
            then(secondHandler).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("등록된 모든 핸들러가 userId로 호출된다")
        void callsEveryHandler() {
            givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            then(firstHandler).should().handleUserDeletion(USER_ID);
            then(secondHandler).should().handleUserDeletion(USER_ID);
        }

        @Test
        @DisplayName("개인 공간은 모든 핸들러가 끝난 뒤에 지운다 — 핸들러가 지우는 데이터가 공간을 참조한다")
        void deletesWorkspaceAfterHandlers() {
            givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            InOrder order = inOrder(firstHandler, secondHandler, workspaceService);
            order.verify(firstHandler).handleUserDeletion(USER_ID);
            order.verify(secondHandler).handleUserDeletion(USER_ID);
            order.verify(workspaceService).deleteForUser(USER_ID);
        }

        @Test
        @DisplayName("행은 남고 개인정보만 익명화된다")
        void anonymizesUser() {
            User user = givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            assertThat(user.getUserStatus()).isEqualTo(UserStatus.DELETED);
            assertThat(user.getEmail()).isEqualTo("deleted_7@harucut.local");
            assertThat(user.getUsername()).isEqualTo("탈퇴한 사용자");
            assertThat(user.getPassword()).isNull();
            assertThat(user.getProviderId()).isNull();
        }

        @Test
        @DisplayName("refresh 토큰이 삭제된다")
        void revokesRefreshToken() {
            User user = givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            then(refreshTokenService).should().revokeAll(user.getPublicId());
        }

        @Test
        @DisplayName("프로필이 업로드된 이미지면 삭제 기록을 남긴다 — S3 가 실패해도 재시도한다")
        void publishesProfileImageDeletion() {
            User user = givenDeleteRequestedUserById(USER_ID);
            user.changeProfileImageUrl("uploads/users/abc/profile/me.png");

            service.exit(USER_ID);

            then(s3Deleter).should().deleteAfterCommit(List.of("uploads/users/abc/profile/me.png"));
        }

        @Test
        @DisplayName("프로필이 기본 이미지면 지울 key 가 없다")
        void skipsDefaultProfileImage() {
            givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            then(s3Deleter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("provider를 지원하는 unlink가 익명화 전에 호출된다")
        void unlinksBeforeAnonymization() {
            givenDeleteRequestedKakaoUser(USER_ID);
            given(unlinkService.supports(Provider.KAKAO)).willReturn(true);
            // delete()가 providerId를 지우기 전에 불려야 한다 — 호출 시점의 상태를 여기서 박제한다
            willAnswer(invocation -> {
                User target = invocation.getArgument(0);
                assertThat(target.getProviderId()).isEqualTo("kakao-123");
                assertThat(target.getUserStatus()).isEqualTo(UserStatus.DELETED_REQUESTED);
                return null;
            }).given(unlinkService).unlink(any(User.class));

            service.exit(USER_ID);

            then(unlinkService).should().unlink(any(User.class));
        }

        @Test
        @DisplayName("provider를 지원하는 unlink가 없으면 그냥 지나간다")
        void skipsUnlinkForUnsupportedProvider() {
            givenDeleteRequestedUserById(USER_ID);

            service.exit(USER_ID);

            then(unlinkService).should(never()).unlink(any(User.class));
        }

        @Test
        @DisplayName("unlink 실패는 전파되고 익명화·토큰 삭제가 일어나지 않는다")
        void unlinkFailureAbortsExit() {
            User user = givenDeleteRequestedKakaoUser(USER_ID);
            given(unlinkService.supports(Provider.KAKAO)).willReturn(true);
            willThrow(new BusinessException(AuthErrorCode.OAUTH2_UNLINK_FAILED))
                    .given(unlinkService).unlink(any(User.class));

            assertThatThrownBy(() -> service.exit(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(AuthErrorCode.OAUTH2_UNLINK_FAILED);

            // 배치에서는 이 예외로 청크가 롤백·스킵되고 다음 날 재시도된다
            assertThat(user.getUserStatus()).isEqualTo(UserStatus.DELETED_REQUESTED);
            then(refreshTokenService).shouldHaveNoInteractions();
        }

        private User givenDeleteRequestedKakaoUser(Long userId) {
            User user = UserFixtures.socialUser("kakao-exit@harucut.com", Provider.KAKAO, "kakao-123");
            ReflectionTestUtils.setField(user, "id", userId);
            user.deleteRequested(NOW.minusDays(8));
            given(userRepository.findById(userId)).willReturn(Optional.of(user));
            return user;
        }

        private User givenDeleteRequestedUserById(Long userId) {
            User user = UserFixtures.localUser("exit@harucut.com", "encoded");
            ReflectionTestUtils.setField(user, "id", userId);
            user.deleteRequested(NOW.minusDays(8));
            // exit는 핸들러 전(검증)과 후(익명화)로 두 번 로드한다 — 목이라 같은 인스턴스가 두 번 나간다
            given(userRepository.findById(userId)).willReturn(Optional.of(user));
            return user;
        }
    }

    // 탈퇴 요청은 잠금 읽기로 시작한다
    private User givenActiveUserLocked() {
        User user = UserFixtures.localUser("exit@harucut.com", "encoded");
        given(userRepository.lockByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
        return user;
    }

    private User givenActiveUser() {
        User user = UserFixtures.localUser("exit@harucut.com", "encoded");
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
        return user;
    }

    // 실제 전이 메서드로 상태를 만든다 — 픽스처로 상태만 심으면 deleteRequestedAt이 비어 있어 검증이 약해진다
    private User givenDeleteRequestedUser() {
        User user = UserFixtures.localUser("exit@harucut.com", "encoded");
        user.deleteRequested(NOW.minusDays(1));
        given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
        return user;
    }
}