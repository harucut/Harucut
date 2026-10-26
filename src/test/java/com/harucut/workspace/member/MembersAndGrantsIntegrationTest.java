package com.harucut.workspace.member;

import com.harucut.audit.dto.AuditLogPage;
import com.harucut.audit.service.AuditLogService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.enums.InvitationKind;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.enums.WorkspaceType;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.MembershipService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * P2B-1 — 멤버 초대·역할·회수, 행사 고객 초대·EventGrant, 감사 로그를 실제 JPA 로 본다(ADR-0008 §2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("멤버·행사 고객 — 통합")
class MembersAndGrantsIntegrationTest {

    @Autowired private MembershipService membershipService;
    @Autowired private InvitationService invitationService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private EventService eventService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AuditLogService auditLogService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private User designer;
    private User customer;
    private User stranger;
    private WorkspaceResponse org;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        designer = userRepository.save(User.localUser("designer@harucut.com", "e", "디자이너"));
        customer = userRepository.save(User.localUser("customer@harucut.com", "e", "신부"));
        stranger = userRepository.save(User.localUser("stranger@harucut.com", "e", "남"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
    }

    @Test
    @DisplayName("멤버 초대: 미리보기(소비 없음) → 수락 → 재사용 MEMBER-002, 이미 멤버면 토큰을 쓰지 않고 MEMBER-003")
    void memberInvitation() {
        MemberDtos.InvitationIssued issued = invite(WorkspaceRole.DESIGNER);

        MemberDtos.InvitationPreview preview = invitationService.preview(designer.getPublicId(), issued.inviteToken());
        assertThat(preview.kind()).isEqualTo(InvitationKind.WORKSPACE);
        assertThat(preview.workspaceName()).isEqualTo("웨딩홀");
        assertThat(preview.role()).isEqualTo(WorkspaceRole.DESIGNER);
        assertThat(preview.invitedByName()).isEqualTo("오너");

        MemberDtos.InvitationAccepted accepted = invitationService.accept(designer.getPublicId(), issued.inviteToken());
        assertThat(accepted.workspaceId()).isEqualTo(org.workspaceId());
        assertThat(membershipService.members(designer.getPublicId(), org.workspaceId()))
                .extracting(MemberDtos.Member::name).containsExactlyInAnyOrder("오너", "디자이너");
        assertCode(() -> invitationService.accept(stranger.getPublicId(), issued.inviteToken()), MemberErrorCode.INVITATION_INVALID);

        // 이미 멤버인 사람이 다른 초대를 열면 그 토큰은 쓰이지 않아 다른 사람이 쓸 수 있다
        MemberDtos.InvitationIssued second = invite(WorkspaceRole.VIEWER);
        assertCode(() -> invitationService.accept(designer.getPublicId(), second.inviteToken()), MemberErrorCode.ALREADY_MEMBER);
        assertThat(invitationService.accept(stranger.getPublicId(), second.inviteToken()).role()).isEqualTo(WorkspaceRole.VIEWER);
    }

    @Test
    @DisplayName("기한 지난 초대 MEMBER-001, 회수한 초대 MEMBER-002, 개인 공간 초대는 GEN-002, OWNER 초대는 OWNER 만")
    void invitationGuards() {
        MemberDtos.InvitationIssued expired = invite(WorkspaceRole.VIEWER);
        entityManager.flush();
        jdbcTemplate.update("update workspace_invitation set expires_at = ? where public_id = ?",
                LocalDateTime.now().minusMinutes(1), expired.invitationId());
        entityManager.clear();
        assertCode(() -> invitationService.preview(stranger.getPublicId(), expired.inviteToken()), MemberErrorCode.INVITATION_EXPIRED);

        MemberDtos.InvitationIssued revoked = invite(WorkspaceRole.VIEWER);
        membershipService.revokeInvitation(owner.getPublicId(), org.workspaceId(), revoked.invitationId());
        assertCode(() -> invitationService.accept(stranger.getPublicId(), revoked.inviteToken()), MemberErrorCode.INVITATION_INVALID);

        String personal = workspaceService.listMine(owner.getPublicId()).stream()
                .filter(w -> w.type() == WorkspaceType.PERSONAL).findFirst().orElseThrow().workspaceId();
        assertCode(() -> membershipService.invite(owner.getPublicId(), personal,
                new MemberDtos.MemberInvitationRequest(WorkspaceRole.VIEWER, null)), GlobalErrorCode.INVALID_INPUT_VALUE);

        join(designer, WorkspaceRole.ADMIN);
        assertCode(() -> membershipService.invite(designer.getPublicId(), org.workspaceId(),
                new MemberDtos.MemberInvitationRequest(WorkspaceRole.OWNER, null)), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> membershipService.invite(stranger.getPublicId(), org.workspaceId(),
                new MemberDtos.MemberInvitationRequest(WorkspaceRole.VIEWER, null)), GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("역할: 마지막 OWNER 는 내리거나 나갈 수 없다(MEMBER-004). OWNER 를 하나 더 두면 된다")
    void lastOwner() {
        assertCode(() -> membershipService.changeRole(owner.getPublicId(), org.workspaceId(), owner.getPublicId(),
                WorkspaceRole.ADMIN), MemberErrorCode.LAST_OWNER);
        assertCode(() -> membershipService.remove(owner.getPublicId(), org.workspaceId(), "me"), MemberErrorCode.LAST_OWNER);

        join(designer, WorkspaceRole.ADMIN);
        // ADMIN 은 OWNER 를 만들 수 없다
        assertCode(() -> membershipService.changeRole(designer.getPublicId(), org.workspaceId(), designer.getPublicId(),
                WorkspaceRole.OWNER), GlobalErrorCode.FORBIDDEN);
        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), designer.getPublicId(), WorkspaceRole.OWNER);
        membershipService.remove(owner.getPublicId(), org.workspaceId(), "me");
        assertThat(membershipService.members(designer.getPublicId(), org.workspaceId()))
                .singleElement().satisfies(m -> assertThat(m.role()).isEqualTo(WorkspaceRole.OWNER));
    }

    @Test
    @DisplayName("행사: 만든 사람은 네 권한, 고객 초대는 그 행사 권한만(공간 멤버 아님), 다시 받으면 합친다, 멤버 회수는 권한도 회수")
    void eventGrants() {
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "grant-event", null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(eventGrantService.myEvents(owner.getPublicId())).singleElement().satisfies(mine -> {
            assertThat(mine.eventId()).isEqualTo(event.eventId());
            assertThat(mine.access().role()).isEqualTo(WorkspaceRole.OWNER);
            assertThat(mine.access().permissions()).containsExactlyInAnyOrderElementsOf(PhotoPermission.all());
        });

        MemberDtos.InvitationIssued issued = eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(EnumSet.of(PhotoPermission.VIEW_PHOTOS, PhotoPermission.DOWNLOAD_PHOTOS), "신부"));
        MemberDtos.InvitationPreview preview = invitationService.preview(customer.getPublicId(), issued.inviteToken());
        assertThat(preview.eventName()).isEqualTo("민지 ♥ 준호");
        assertThat(preview.permissions()).containsExactlyInAnyOrder(PhotoPermission.VIEW_PHOTOS, PhotoPermission.DOWNLOAD_PHOTOS);
        invitationService.accept(customer.getPublicId(), issued.inviteToken());

        assertThat(eventGrantService.myEvents(customer.getPublicId())).singleElement().satisfies(mine -> {
            assertThat(mine.access().role()).isNull();
            assertThat(mine.access().permissions()).containsExactlyInAnyOrder(PhotoPermission.VIEW_PHOTOS, PhotoPermission.DOWNLOAD_PHOTOS);
        });
        // 고객은 공간 멤버가 아니다 — 공간은 존재를 모르고(404), 사진 권한이 있는 행사는 알지만 운영 권한이 없다(403)
        assertCode(() -> membershipService.members(customer.getPublicId(), org.workspaceId()), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> eventGrantService.grants(customer.getPublicId(), event.eventId()), GlobalErrorCode.FORBIDDEN);

        // 같은 권한이면 MEMBER-003, 더 넓은 권한이면 합친다
        assertCode(() -> invitationService.accept(customer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(),
                event.eventId(), new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken()),
                MemberErrorCode.ALREADY_MEMBER);
        invitationService.accept(customer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.BULK_DOWNLOAD), null)).inviteToken());
        assertThat(eventGrantService.grants(owner.getPublicId(), event.eventId()))
                .filteredOn(g -> g.userId().equals(customer.getPublicId())).singleElement()
                .satisfies(g -> assertThat(g.permissions()).containsExactlyInAnyOrder(
                        PhotoPermission.VIEW_PHOTOS, PhotoPermission.DOWNLOAD_PHOTOS, PhotoPermission.BULK_DOWNLOAD));

        // 공간 멤버를 회수하면 그 사람의 이 공간 행사 권한도 사라진다. 다시 초대하면 같은 멤버 행이 살아난다
        // 권한 바꾸기는 집합을 통째로 바꾼다
        Long customerGrant = eventGrantService.grants(owner.getPublicId(), event.eventId()).stream()
                .filter(g -> g.userId().equals(customer.getPublicId())).findFirst().orElseThrow().grantId();
        assertThat(eventGrantService.update(owner.getPublicId(), event.eventId(), customerGrant,
                Set.of(PhotoPermission.VIEW_PHOTOS)).permissions()).containsExactly(PhotoPermission.VIEW_PHOTOS);

        join(designer, WorkspaceRole.ADMIN);
        invitationService.accept(designer.getPublicId(), eventGrantService.inviteCustomer(owner.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken());
        membershipService.remove(owner.getPublicId(), org.workspaceId(), designer.getPublicId());
        assertThat(eventGrantService.myEvents(designer.getPublicId())).isEmpty();
        join(designer, WorkspaceRole.VIEWER);
        assertThat(membershipService.members(owner.getPublicId(), org.workspaceId()))
                .filteredOn(m -> m.userId().equals(designer.getPublicId())).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(WorkspaceRole.VIEWER));
    }

    @Test
    @DisplayName("감사 로그: 초대·가입·행사 생성이 남고, 공간 단위로 최신순")
    void auditLog() {
        join(designer, WorkspaceRole.DESIGNER);
        eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("행사", "audit-event", null, null, null, null, null, null, null, null, null, null, null, null));
        Long workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();

        AuditLogPage page = auditLogService.workspacePage(workspaceId, null);

        assertThat(page.items()).extracting(AuditLogPage.Item::action)
                .containsExactly("EVENT_CREATED", "MEMBER_JOINED", "MEMBER_INVITED");
        assertThat(page.items().get(0).actorName()).isEqualTo("오너");
        assertThat(page.nextCursor()).isNull();
    }

    // ── helpers ──────────────────────────────

    private MemberDtos.InvitationIssued invite(WorkspaceRole role) {
        return membershipService.invite(owner.getPublicId(), org.workspaceId(), new MemberDtos.MemberInvitationRequest(role, null));
    }

    private void join(User user, WorkspaceRole role) {
        invitationService.accept(user.getPublicId(), invite(role).inviteToken());
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
