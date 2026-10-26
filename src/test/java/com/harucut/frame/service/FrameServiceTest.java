package com.harucut.frame.service;

import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.draft.service.FrameDraftCleaner;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.policy.FrameSubscriptionPolicy;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.subscription.exception.SubscriptionErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrameService")
class FrameServiceTest {

    private static final String PUBLIC_ID = "AbCdEf12Gh";
    private static final Long FRAME_ID = 10L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 10, 12, 0);
    private static final BackgroundAttributes COLOR = new BackgroundAttributes.Color("#FFE4E1");

    @Mock
    private FrameRepository frameRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FrameVersionCleaner frameVersionCleaner;

    @Mock
    private FrameDraftCleaner frameDraftCleaner;

    @Mock
    private FrameComponentAssembler frameComponentAssembler;

    @Mock
    private FrameSubscriptionPolicy frameSubscriptionPolicy;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private FrameService frameService;

    private User user;

    @BeforeEach
    void setUp() {
        frameService = new FrameService(frameRepository, workspaceRepository, userRepository,
                frameComponentAssembler, frameSubscriptionPolicy, workspaceService, frameVersionCleaner, frameDraftCleaner,
                transactionManager);
        user = User.localUser("user@harucut.com", "encoded", "하루컷");
        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Nested
    @DisplayName("createFrame")
    class CreateFrame {

        @Test
        @DisplayName("한도를 통과하면 조립 → 저장 → 응답 순으로 흐른다")
        void createsOwnedFrame() {
            FrameCreateRequest request = request();
            Frame frame = ownedFrame(CREATED);
            FrameResponse expected = response(1L);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userRepository.lockByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.countPersonalByUser(user)).willReturn(2L);
            Workspace personal = Workspace.personal(1L);
            ReflectionTestUtils.setField(personal, "id", 77L);
            given(workspaceService.personalWorkspaceOf(1L)).willReturn(personal);
            given(frameComponentAssembler.assembleOwned(user, request, 77L)).willReturn(frame);
            given(frameRepository.save(frame)).willReturn(frame);
            given(frameComponentAssembler.toFrameResponse(frame)).willReturn(expected);

            assertThat(frameService.createFrame(PUBLIC_ID, request)).isEqualTo(expected);
            // 한도 판정에 "현재 보관 총량"이 정확히 전달된다
            then(frameSubscriptionPolicy).should().assertFrameRetentionLimit(1L, 2);
            // 새 프레임은 사용자의 개인 공간에 속한다
            assertThat(frame.getWorkspaceId()).isEqualTo(77L);
        }

        @Test
        @DisplayName("조직 공간 프레임은 개인 요금제 한도를 보지 않고 공간 상한만 본다")
        void organizationFrameSkipsPersonalPlan() {
            FrameCreateRequest request = new FrameCreateRequest("제목", null, "uploads/p.png", FrameType.CLASSIC,
                    null, null, COLOR, null, null, null, "WsOrg");
            Frame frame = ownedFrame(CREATED);
            Workspace org = Workspace.organization("웨딩팀");
            ReflectionTestUtils.setField(org, "id", 88L);
            given(userRepository.lockByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(workspaceService.requireRole(eq("WsOrg"), eq(1L), any())).willReturn(org);
            given(frameRepository.countByWorkspaceId(88L)).willReturn(5L);
            given(frameComponentAssembler.assembleOwned(user, request, 88L)).willReturn(frame);
            given(frameRepository.save(frame)).willReturn(frame);

            frameService.createFrame(PUBLIC_ID, request);

            then(frameSubscriptionPolicy).shouldHaveNoInteractions();
            then(frameComponentAssembler).should().assertWorkspaceRoom(5L);
            assertThat(frame.getWorkspaceId()).isEqualTo(88L);
        }

        @Test
        @DisplayName("한도 초과면 SUBS-003이 전파되고 조립·저장 어느 것도 일어나지 않는다")
        void limitExceeded() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(userRepository.lockByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.countPersonalByUser(user)).willReturn(3L);
            given(workspaceService.personalWorkspaceOf(1L)).willReturn(Workspace.personal(1L));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED))
                    .given(frameSubscriptionPolicy).assertFrameRetentionLimit(1L, 3);

            assertThatThrownBy(() -> frameService.createFrame(PUBLIC_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED);

            then(frameComponentAssembler).shouldHaveNoInteractions();
            then(frameRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("사용자가 없으면 GEN-031이다")
        void userNotFound() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> frameService.createFrame(PUBLIC_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getMyFrames — cutoff 필터, 소프트 캡, 순서")
    class GetMyFrames {

        @Test
        @DisplayName("보관 기간이 지난 프레임은 빠지고, 정확히 cutoff인 프레임은 살아남는다")
        void cutoffFiltersOldFrames() {
            LocalDateTime cutoff = LocalDateTime.of(2026, 8, 5, 12, 0);
            Frame recent = ownedFrame(cutoff.plusDays(3));
            Frame exactlyAtCutoff = ownedFrame(cutoff);
            Frame expired = ownedFrame(cutoff.minusNanos(1));
            FrameResponse recentResponse = response(1L);
            FrameResponse cutoffResponse = response(2L);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(cutoff);
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(null);
            given(frameRepository.findPersonalWithComponentsByUser(user))
                    .willReturn(List.of(recent, exactlyAtCutoff, expired));
            given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of());
            given(frameComponentAssembler.toFrameResponse(recent)).willReturn(recentResponse);
            given(frameComponentAssembler.toFrameResponse(exactlyAtCutoff)).willReturn(cutoffResponse);

            assertThat(frameService.getMyFrames(PUBLIC_ID))
                    .containsExactly(recentResponse, cutoffResponse);
        }

        @Test
        @DisplayName("소프트 캡은 최신 cap개만 남긴다 — 잘린 프레임은 변환조차 되지 않는다")
        void capLimitsToNewest() {
            Frame first = ownedFrame(CREATED.plusDays(3));
            Frame second = ownedFrame(CREATED.plusDays(2));
            Frame third = ownedFrame(CREATED.plusDays(1));
            Frame capped = ownedFrame(CREATED);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(null);
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(3);
            given(frameRepository.findPersonalWithComponentsByUser(user))
                    .willReturn(List.of(first, second, third, capped));
            given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of());
            given(frameComponentAssembler.toFrameResponse(first)).willReturn(response(1L));
            given(frameComponentAssembler.toFrameResponse(second)).willReturn(response(2L));
            given(frameComponentAssembler.toFrameResponse(third)).willReturn(response(3L));

            assertThat(frameService.getMyFrames(PUBLIC_ID))
                    .containsExactly(response(1L), response(2L), response(3L));
        }

        @Test
        @DisplayName("무제한이면 전량이 나오고 시스템 프레임이 항상 뒤에 붙는다")
        void unlimitedAndSystemAppended() {
            Frame mine = ownedFrame(CREATED);
            Frame system = systemFrame();
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameSubscriptionPolicy.resolveHistoryCutoff(1L)).willReturn(null);
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(null);
            given(frameRepository.findPersonalWithComponentsByUser(user)).willReturn(List.of(mine));
            given(frameRepository.findAllWithComponentsBySystem()).willReturn(List.of(system));
            given(frameComponentAssembler.toFrameResponse(mine)).willReturn(response(1L));
            given(frameComponentAssembler.toFrameResponse(system)).willReturn(response(99L));

            assertThat(frameService.getMyFrames(PUBLIC_ID))
                    .containsExactly(response(1L), response(99L));
        }
    }

    @Nested
    @DisplayName("getFrame — 소유권 → 보관 기간 → 소프트 캡")
    class GetFrame {

        @Test
        @DisplayName("시스템 프레임은 정책 검사를 전부 우회한다")
        void systemFrameBypassesAllChecks() {
            Frame system = systemFrame();
            FrameResponse expected = response(99L);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(system));
            given(frameComponentAssembler.toFrameResponse(system)).willReturn(expected);

            assertThat(frameService.getFrame(PUBLIC_ID, FRAME_ID)).isEqualTo(expected);
            then(frameSubscriptionPolicy).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("남의 프레임은 GEN-031이고 정책 검사까지 가지도 않는다 — 소유권이 첫 관문")
        void othersFrameHidden() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(othersFrame()));

            assertThatThrownBy(() -> frameService.getFrame(PUBLIC_ID, FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);

            then(frameSubscriptionPolicy).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("보관 기간 밖이면 SUBS-002이고 캡 판정까지 가지 않는다")
        void beyondRetention() {
            Frame frame = ownedFrame(CREATED);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(frameSubscriptionPolicy).assertHistoryAccessible(1L, CREATED);

            assertThatThrownBy(() -> frameService.getFrame(PUBLIC_ID, FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED);

            then(frameSubscriptionPolicy).should(never()).resolveFrameRetentionCap(any());
        }

        @Test
        @DisplayName("자기보다 최신인 프레임이 정확히 cap개면 SUBS-003이다 — 경계")
        void capBlocksAtBoundary() {
            Frame frame = ownedFrame(CREATED);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(3);
            given(frameRepository.countPersonalByUserCreatedAfter(user, CREATED)).willReturn(3L);

            assertThatThrownBy(() -> frameService.getFrame(PUBLIC_ID, FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SubscriptionErrorCode.PLAN_FRAME_RETENTION_EXCEEDED);
        }

        @Test
        @DisplayName("최신 프레임이 cap 미만이면 조회된다 — 경계의 수용 쌍")
        void capAllowsBelowBoundary() {
            Frame frame = ownedFrame(CREATED);
            FrameResponse expected = response(1L);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(3);
            given(frameRepository.countPersonalByUserCreatedAfter(user, CREATED)).willReturn(2L);
            given(frameComponentAssembler.toFrameResponse(frame)).willReturn(expected);

            assertThat(frameService.getFrame(PUBLIC_ID, FRAME_ID)).isEqualTo(expected);
        }

        @Test
        @DisplayName("프레임이 없으면 GEN-031이다")
        void frameNotFound() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> frameService.getFrame(PUBLIC_ID, FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("updateFrame — 관문 통과 후 공용 교체 위임")
    class UpdateFrame {

        @Test
        @DisplayName("관문을 통과하면 교체 → flush → 응답 조립 순으로 위임한다")
        void replacesAfterGuards() {
            Frame frame = ownedFrame(CREATED);
            FrameCreateRequest request = request();
            FrameResponse expected = response(1L);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameSubscriptionPolicy.resolveFrameRetentionCap(1L)).willReturn(null);
            given(frameComponentAssembler.toFrameResponse(frame)).willReturn(expected);

            assertThat(frameService.updateFrame(PUBLIC_ID, FRAME_ID, request)).isEqualTo(expected);

            // 응답 조립은 flush 뒤 — 새 컴포넌트 id가 채워진 다음이어야 한다
            InOrder inOrder = inOrder(frameComponentAssembler, frameRepository);
            inOrder.verify(frameComponentAssembler).replaceContent(frame, request, user);
            inOrder.verify(frameRepository).saveAndFlush(frame);
            inOrder.verify(frameComponentAssembler).toFrameResponse(frame);
        }

        @Test
        @DisplayName("보관 기간 밖 프레임은 덮어쓸 수 없다 — SUBS-002 (조회와 같은 관문)")
        void hiddenFrameCannotBeUpdated() {
            Frame frame = ownedFrame(CREATED);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            willThrow(new BusinessException(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED))
                    .given(frameSubscriptionPolicy).assertHistoryAccessible(1L, CREATED);

            assertThatThrownBy(() -> frameService.updateFrame(PUBLIC_ID, FRAME_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(SubscriptionErrorCode.PLAN_HISTORY_RETENTION_EXCEEDED);

            then(frameComponentAssembler).shouldHaveNoInteractions();
            then(frameRepository).should(never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("남의 프레임 수정은 GEN-031이고 아무것도 바뀌지 않는다")
        void othersFrameHidden() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(othersFrame()));

            assertThatThrownBy(() -> frameService.updateFrame(PUBLIC_ID, FRAME_ID, request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);

            then(frameComponentAssembler).shouldHaveNoInteractions();
            then(frameRepository).should(never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("deleteFrame — 소유권만 검사")
    class DeleteFrame {

        @Test
        @DisplayName("수집된 key 전부를 예약하고 행을 지운다. 정책 검사는 없다 — 숨겨진 프레임도 정리 가능")
        void deletesWithAllKeys() {
            Frame frame = ownedFrame(CREATED);
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(frame));
            given(frameComponentAssembler.collectAllKeys(frame))
                    .willReturn(List.of("uploads/k1.png", "uploads/bg.png", "uploads/preview.png"));

            frameService.deleteFrame(PUBLIC_ID, FRAME_ID);

            ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
            then(frameComponentAssembler).should().scheduleDeletion(captor.capture());
            assertThat(captor.getValue())
                    .containsExactly("uploads/k1.png", "uploads/bg.png", "uploads/preview.png");
            // 발행본이 프레임을 FK 로 참조한다 — 프레임보다 먼저
            then(frameVersionCleaner).should().deleteForFrames(List.of(FRAME_ID));
            then(frameDraftCleaner).should().deleteForFrames(List.of(FRAME_ID));
            then(frameRepository).should().delete(frame);
            then(frameSubscriptionPolicy).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("남의 프레임 삭제는 GEN-031이고 아무것도 지워지지 않는다")
        void othersFrameHidden() {
            given(userRepository.findByPublicId(PUBLIC_ID)).willReturn(Optional.of(user));
            given(frameRepository.findById(FRAME_ID)).willReturn(Optional.of(othersFrame()));

            assertThatThrownBy(() -> frameService.deleteFrame(PUBLIC_ID, FRAME_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(GlobalErrorCode.NOT_FOUND);

            then(frameRepository).should(never()).delete(any(Frame.class));
            then(frameVersionCleaner).shouldHaveNoInteractions();
        }
    }

    // ── fixtures ──────────────────────────────

    private Frame ownedFrame(LocalDateTime createdAt) {
        Frame frame = Frame.owned(user, "제목", "설명", "uploads/p.png", FrameType.CLASSIC, COLOR);
        ReflectionTestUtils.setField(frame, "id", FRAME_ID);
        ReflectionTestUtils.setField(frame, "createdAt", createdAt);
        return frame;
    }

    private Frame othersFrame() {
        User other = User.localUser("other@harucut.com", "encoded", "남");
        ReflectionTestUtils.setField(other, "id", 2L);
        Frame frame = Frame.owned(other, "남의 것", "설명", "uploads/p.png", FrameType.CLASSIC, COLOR);
        ReflectionTestUtils.setField(frame, "id", FRAME_ID);
        ReflectionTestUtils.setField(frame, "createdAt", CREATED);
        return frame;
    }

    private static Frame systemFrame() {
        Frame frame = Frame.system("기본", "설명", "uploads/p.png", FrameType.CLASSIC, COLOR);
        ReflectionTestUtils.setField(frame, "id", 99L);
        return frame;
    }

    private static FrameCreateRequest request() {
        return new FrameCreateRequest("제목", null, "uploads/p.png", FrameType.CLASSIC, null, null,
                COLOR, null, null);
    }

    private static FrameResponse response(Long frameId) {
        return new FrameResponse(frameId, "제목", "설명", "https://preview", FrameType.CLASSIC,
                2000, 6000, COLOR, List.of(false, false, false, false), List.of(), false, 0L, null);
    }
}
