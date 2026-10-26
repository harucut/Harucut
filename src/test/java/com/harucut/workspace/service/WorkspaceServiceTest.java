package com.harucut.workspace.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.MembershipStatus;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.enums.WorkspaceType;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceService")
class WorkspaceServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PersonalWorkspaceCreator personalWorkspaceCreator;

    @InjectMocks
    private WorkspaceService workspaceService;

    private Workspace personal;

    @BeforeEach
    void setUp() {
        personal = Workspace.personal(USER_ID);
        ReflectionTestUtils.setField(personal, "id", 10L);
    }

    @Nested
    @DisplayName("personalWorkspaceOf — 지연 생성")
    class PersonalWorkspace {

        @Test
        @DisplayName("이미 있으면 그대로 돌려주고 만들지 않는다")
        void existing() {
            given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.of(personal));

            assertThat(workspaceService.personalWorkspaceOf(USER_ID)).isSameAs(personal);
            then(personalWorkspaceCreator).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("없으면 새 트랜잭션에서 만든다")
        void createsWhenAbsent() {
            given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.empty());
            given(personalWorkspaceCreator.create(USER_ID)).willReturn(personal);

            assertThat(workspaceService.personalWorkspaceOf(USER_ID)).isSameAs(personal);
        }

        @Test
        @DisplayName("동시 요청이 먼저 만들어 유니크에 걸리면 새 트랜잭션에서 다시 읽는다")
        void raceReadsWinner() {
            given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.empty());
            given(personalWorkspaceCreator.create(USER_ID))
                    .willThrow(new DataIntegrityViolationException("uk_workspace_personal_owner"));
            given(personalWorkspaceCreator.find(USER_ID)).willReturn(personal);

            assertThat(workspaceService.personalWorkspaceOf(USER_ID)).isSameAs(personal);
        }
    }

    @Test
    @DisplayName("listMine: 개인 공간을 보장한 뒤 활성 멤버십의 공간과 역할을 돌려준다")
    void listMine() {
        User user = User.localUser("me@harucut.com", "encoded", "나");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.findByPublicId(user.getPublicId())).willReturn(Optional.of(user));
        given(workspaceRepository.findByPersonalOwnerUserId(USER_ID)).willReturn(Optional.of(personal));
        given(membershipRepository.findAllByUserIdAndStatus(USER_ID, MembershipStatus.ACTIVE))
                .willReturn(List.of(WorkspaceMembership.owner(10L, USER_ID)));
        given(workspaceRepository.findAllByIdIn(anyList())).willReturn(List.of(personal));

        List<WorkspaceResponse> mine = workspaceService.listMine(user.getPublicId());

        assertThat(mine).singleElement().satisfies(w -> {
            assertThat(w.workspaceId()).isEqualTo(personal.getPublicId());
            assertThat(w.type()).isEqualTo(WorkspaceType.PERSONAL);
            assertThat(w.role()).isEqualTo(WorkspaceRole.OWNER);
        });
    }

    @Nested
    @DisplayName("requireRole — 공간 권한")
    class RequireRole {

        @Test
        @DisplayName("멤버이고 역할이 맞으면 공간을 돌려준다")
        void allowed() {
            given(workspaceRepository.findByPublicId("Ws")).willReturn(Optional.of(personal));
            given(membershipRepository.findByWorkspaceIdAndUserIdAndStatus(10L, USER_ID, MembershipStatus.ACTIVE))
                    .willReturn(Optional.of(WorkspaceMembership.owner(10L, USER_ID)));

            assertThat(workspaceService.requireRole("Ws", USER_ID, Set.of(WorkspaceRole.OWNER))).isSameAs(personal);
        }

        @Test
        @DisplayName("멤버가 아니면 404 — 공간이 있다는 사실도 알리지 않는다")
        void notMember() {
            given(workspaceRepository.findByPublicId("Ws")).willReturn(Optional.of(personal));
            given(membershipRepository.findByWorkspaceIdAndUserIdAndStatus(10L, USER_ID, MembershipStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.requireRole("Ws", USER_ID, Set.of(WorkspaceRole.OWNER)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("역할이 모자라도 404 다")
        void insufficientRole() {
            given(workspaceRepository.findByPublicId("Ws")).willReturn(Optional.of(personal));
            given(membershipRepository.findByWorkspaceIdAndUserIdAndStatus(10L, USER_ID, MembershipStatus.ACTIVE))
                    .willReturn(Optional.of(WorkspaceMembership.owner(10L, USER_ID)));

            assertThatThrownBy(() -> workspaceService.requireRole("Ws", USER_ID, Set.of(WorkspaceRole.VIEWER)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("없는 공간은 404 다")
        void unknownWorkspace() {
            given(workspaceRepository.findByPublicId("Nope")).willReturn(Optional.empty());

            assertThatThrownBy(() -> workspaceService.requireRole("Nope", USER_ID, Set.of(WorkspaceRole.OWNER)))
                    .isInstanceOf(BusinessException.class);
            then(membershipRepository).should(never()).findByWorkspaceIdAndUserIdAndStatus(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @DisplayName("deleteForUser: 멤버십을 먼저, 개인 공간을 나중에 지운다(FK)")
    void deleteForUser() {
        workspaceService.deleteForUser(USER_ID);

        InOrder order = inOrder(membershipRepository, workspaceRepository);
        order.verify(membershipRepository).deleteByUserId(USER_ID);
        order.verify(workspaceRepository).deletePersonalByOwner(USER_ID);
    }
}
