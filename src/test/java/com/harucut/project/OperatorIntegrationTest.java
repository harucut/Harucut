package com.harucut.project;

import com.harucut.booth.service.BoothDeviceService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.EventGrant;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventAccessPolicy;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.repository.ProjectMembershipRepository;
import com.harucut.project.repository.ProjectRepository;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.MembershipService;
import com.harucut.workspace.service.WorkspaceService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * P3-2 — 공간 역할 OPERATOR 와 행사·프로젝트 단위 운영 권한 위임(ADR-0009 §3).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("권한 위임(OPERATOR) — 통합")
class OperatorIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private ProjectService projectService;
    @Autowired private BoothDeviceService boothDeviceService;
    @Autowired private MembershipService membershipService;
    @Autowired private InvitationService invitationService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private ProjectMembershipRepository projectMembershipRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventGrantRepository grantRepository;
    @Autowired private EventAccessPolicy accessPolicy;
    @Autowired private PhotoService photoService;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private User operator;
    private WorkspaceResponse org;
    private Long workspaceId;
    private Long frameVersionId;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        // 멤버 초대로 OPERATOR 가 된다
        operator = userRepository.save(User.localUser("op@harucut.com", "e", "운영 담당"));
        invitationService.accept(operator.getPublicId(), membershipService.invite(owner.getPublicId(), org.workspaceId(),
                new MemberDtos.MemberInvitationRequest(WorkspaceRole.OPERATOR, null)).inviteToken());
        frameVersionId = frameVersionService.publish(owner.getPublicId(), frameService.createFrame(owner.getPublicId(),
                new FrameCreateRequest("프레임", null, S3Keys.userRoot(owner.getPublicId()) + "frames/p.png", FrameType.CLASSIC,
                        null, null, new BackgroundAttributes.Color("#FFFFFF"), null, List.of(), null, org.workspaceId())).frameId())
                .frameVersionId();
    }

    @Test
    @DisplayName("배정 전: 공간은 OPERATOR 로 보이지만 행사·프로젝트는 비어 있고, 행사는 404, 멤버·기본값은 403")
    void beforeAssignment() {
        EventResponse event = ready("before-1", null);

        assertThat(workspaceService.listMine(operator.getPublicId())).anySatisfy(w -> {
            assertThat(w.workspaceId()).isEqualTo(org.workspaceId());
            assertThat(w.role()).isEqualTo(WorkspaceRole.OPERATOR);
        });
        assertThat(eventService.list(operator.getPublicId(), org.workspaceId())).isEmpty();
        assertThat(eventGrantService.myEvents(operator.getPublicId())).isEmpty();
        assertThat(projectService.list(operator.getPublicId(), org.workspaceId())).isEmpty();
        assertCode(() -> eventService.get(operator.getPublicId(), event.eventId()), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> membershipService.members(operator.getPublicId(), org.workspaceId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> projectService.workspaceDefaults(operator.getPublicId(), org.workspaceId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventService.create(operator.getPublicId(), org.workspaceId(), settings("x-1", null)), GlobalErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("행사 단위 배정: MANAGE_EVENT 만 받으면 설정·게시는 되고 부스·고객 초대·프로젝트 변경은 403, 목록·me/events 에 그 행사만")
    void eventLevelAssignment() {
        EventResponse event = ready("event-1", null);
        ready("event-2", null);
        MemberDtos.EventGrantView view = eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), operator.getPublicId(),
                EnumSet.of(OperationPermission.MANAGE_EVENT));
        assertThat(view.operations()).containsExactly(OperationPermission.MANAGE_EVENT);
        assertThat(view.permissions()).isEmpty();

        patch(operator, event, Map.of("name", "운영 담당이 바꾼 이름"));
        assertThat(eventService.publish(operator.getPublicId(), event.eventId()).status().name()).isEqualTo("LIVE");
        assertCode(() -> boothDeviceService.create(operator.getPublicId(), event.eventId(), "부스"), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventGrantService.inviteCustomer(operator.getPublicId(), event.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)), GlobalErrorCode.FORBIDDEN);

        assertThat(eventService.list(operator.getPublicId(), org.workspaceId())).extracting(EventResponse::eventId)
                .containsExactly(event.eventId());
        assertThat(eventGrantService.myEvents(operator.getPublicId())).singleElement().satisfies(mine -> {
            assertThat(mine.access().role()).isEqualTo(WorkspaceRole.OPERATOR);
            assertThat(mine.access().operations()).containsExactly(OperationPermission.MANAGE_EVENT);
            assertThat(mine.access().sources()).containsExactly(Map.entry("MANAGE_EVENT", "EVENT"));
        });
        // OWNER 는 셋 다, sources 없음
        assertThat(eventGrantService.myEvents(owner.getPublicId())).allSatisfy(mine -> {
            assertThat(mine.access().operations()).containsExactlyInAnyOrder(OperationPermission.values());
            assertThat(mine.access().sources()).isNull();
        });

        // 빈 목록이면 운영 권한을 뺀다 — 다시 볼 수 없다
        eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), operator.getPublicId(), Set.of());
        assertCode(() -> eventService.get(operator.getPublicId(), event.eventId()), GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("프로젝트 멤버: 그 프로젝트의 모든 행사에 권한이 합쳐지고(출처 BOTH·PROJECT), 고객 초대는 자기 사진 권한 안에서만, 프로젝트 안에서 행사를 만든다")
    void projectMembership() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        EventResponse inProject = ready("pj-1", project.projectId());
        ready("outside-1", null);
        eventGrantService.assignOperator(owner.getPublicId(), inProject.eventId(), operator.getPublicId(),
                EnumSet.of(OperationPermission.MANAGE_EVENT));
        projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(), new ProjectDtos.MemberRequest(
                EnumSet.of(OperationPermission.MANAGE_EVENT, OperationPermission.MANAGE_BOOTH, OperationPermission.INVITE_CUSTOMERS),
                EnumSet.of(PhotoPermission.VIEW_PHOTOS)));

        assertThat(projectService.list(operator.getPublicId(), org.workspaceId())).extracting(ProjectDtos.Project::projectId)
                .containsExactly(project.projectId());
        assertThat(eventGrantService.myEvents(operator.getPublicId())).singleElement().satisfies(mine -> {
            assertThat(mine.access().operations()).containsExactlyInAnyOrder(OperationPermission.values());
            assertThat(mine.access().permissions()).containsExactly(PhotoPermission.VIEW_PHOTOS);
            assertThat(mine.access().sources()).containsEntry("MANAGE_EVENT", "BOTH").containsEntry("MANAGE_BOOTH", "PROJECT")
                    .containsEntry("VIEW_PHOTOS", "PROJECT");
        });
        assertThat(boothDeviceService.create(operator.getPublicId(), inProject.eventId(), "부스")).isNotNull();
        assertThat(eventGrantService.inviteCustomer(operator.getPublicId(), inProject.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), "고객")).inviteToken()).isNotBlank();
        assertCode(() -> eventGrantService.inviteCustomer(operator.getPublicId(), inProject.eventId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.DOWNLOAD_PHOTOS), null)), GlobalErrorCode.FORBIDDEN);

        // 프로젝트 안에서는 만들 수 있고(그 프로젝트 권한으로 운영), 밖에서는 403
        EventResponse created = eventService.create(operator.getPublicId(), org.workspaceId(), settings("op-made", project.projectId()));
        assertThat(eventService.get(operator.getPublicId(), created.eventId()).settings().projectId()).isEqualTo(project.projectId());
        assertCode(() -> eventService.create(operator.getPublicId(), org.workspaceId(), settings("op-outside", null)), GlobalErrorCode.FORBIDDEN);
        // 프로젝트 변경은 OWNER·ADMIN 만
        assertCode(() -> patch(operator, created, Map.of("projectId", projectService.create(owner.getPublicId(), org.workspaceId(),
                new ProjectDtos.CreateRequest("가을", null)).projectId())), GlobalErrorCode.FORBIDDEN);

        // 프로젝트에서 빼면 상속 권한이 사라지고 행사에서 직접 받은 것만 남는다
        projectService.deleteMember(owner.getPublicId(), project.projectId(), operator.getPublicId());
        assertThat(eventGrantService.myEvents(operator.getPublicId())).singleElement().satisfies(mine -> {
            assertThat(mine.access().operations()).containsExactly(OperationPermission.MANAGE_EVENT);
            assertThat(mine.access().sources()).containsExactly(Map.entry("MANAGE_EVENT", "EVENT"));
        });
    }

    @Test
    @DisplayName("규칙: OPERATOR 가 아닌 멤버에게는 배정 GEN-002, OPERATOR 는 배정 못 함(403), 역할을 내리면 운영 권한이 사라지고, 멤버 회수는 프로젝트 권한도 지운다")
    void rules() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        EventResponse event = ready("rules-1", project.projectId());
        User designer = userRepository.save(User.localUser("d@harucut.com", "e", "디자이너"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, designer.getId(), WorkspaceRole.DESIGNER));

        assertCode(() -> eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), designer.getPublicId(),
                Set.of(OperationPermission.MANAGE_EVENT)), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> projectService.putMember(owner.getPublicId(), project.projectId(), designer.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(OperationPermission.MANAGE_EVENT), Set.of())), GlobalErrorCode.INVALID_INPUT_VALUE);

        projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(EnumSet.of(OperationPermission.MANAGE_EVENT, OperationPermission.INVITE_CUSTOMERS), Set.of()));
        assertCode(() -> eventGrantService.assignOperator(operator.getPublicId(), event.eventId(), operator.getPublicId(),
                Set.of(OperationPermission.MANAGE_BOOTH)), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> projectService.members(operator.getPublicId(), project.projectId()), GlobalErrorCode.FORBIDDEN);

        // 다른 운영자의 행은 OPERATOR 가 바꿀 수 없다
        User other = userRepository.save(User.localUser("op2@harucut.com", "e", "다른 운영 담당"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, other.getId(), WorkspaceRole.OPERATOR));
        Long otherGrant = eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), other.getPublicId(),
                Set.of(OperationPermission.MANAGE_BOOTH)).grantId();
        assertCode(() -> eventGrantService.delete(operator.getPublicId(), event.eventId(), otherGrant), GlobalErrorCode.FORBIDDEN);

        // 역할을 VIEWER 로 내리면 운영 권한이 효력을 잃는다
        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), operator.getPublicId(), WorkspaceRole.VIEWER);
        assertCode(() -> eventService.get(operator.getPublicId(), event.eventId()), GlobalErrorCode.NOT_FOUND);

        // 멤버에서 내보내면 프로젝트 권한도 지운다
        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), operator.getPublicId(), WorkspaceRole.OPERATOR);
        Long projectId = projectRepository.findByPublicId(project.projectId()).orElseThrow().getId();
        assertThat(projectMembershipRepository.findByProjectIdAndUserId(projectId, operator.getId())).isPresent();
        membershipService.remove(owner.getPublicId(), org.workspaceId(), operator.getPublicId());
        assertThat(projectMembershipRepository.findByProjectIdAndUserId(projectId, operator.getId())).isEmpty();
    }

    @Test
    @DisplayName("프로젝트로만 운영 권한을 받은 운영자의 사진 전용 행도 「운영자의 행」 — 다른 OPERATOR 가 PATCH·DELETE 하면 403")
    void projectOnlyOperatorGrantIsProtected() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        EventResponse event = ready("guard-1", project.projectId());
        Long eventId = eventRepository.findByPublicId(event.eventId()).orElseThrow().getId();
        // operator: 행사에서 INVITE_CUSTOMERS(+ 사진 권한)
        eventGrantService.assignOperator(owner.getPublicId(), event.eventId(), operator.getPublicId(),
                EnumSet.of(OperationPermission.INVITE_CUSTOMERS));
        grantRepository.findByEventIdAndUserId(eventId, operator.getId()).orElseThrow().replace(EnumSet.of(PhotoPermission.VIEW_PHOTOS));
        // other: 운영 권한은 프로젝트에서만, 이 행사에는 사진 전용 행(운영 칸은 비어 있다)
        User other = userRepository.save(User.localUser("op2@harucut.com", "e", "다른 운영 담당"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, other.getId(), WorkspaceRole.OPERATOR));
        projectService.putMember(owner.getPublicId(), project.projectId(), other.getPublicId(),
                new ProjectDtos.MemberRequest(EnumSet.of(OperationPermission.MANAGE_EVENT), Set.of()));
        Long otherGrant = grantRepository.save(new EventGrant(eventId, other.getId(), EnumSet.of(PhotoPermission.VIEW_PHOTOS), owner.getId())).getId();
        assertThat(grantRepository.findById(otherGrant).orElseThrow().getOperations()).isEmpty();

        assertCode(() -> eventGrantService.update(operator.getPublicId(), event.eventId(), otherGrant, EnumSet.of(PhotoPermission.VIEW_PHOTOS)),
                GlobalErrorCode.FORBIDDEN);
        assertCode(() -> eventGrantService.delete(operator.getPublicId(), event.eventId(), otherGrant), GlobalErrorCode.FORBIDDEN);
        // 공간 OWNER 의 행도 운영자의 행이다
        Long ownerGrant = grantRepository.findByEventIdAndUserId(eventId, owner.getId()).orElseThrow().getId();
        assertCode(() -> eventGrantService.delete(operator.getPublicId(), event.eventId(), ownerGrant), GlobalErrorCode.FORBIDDEN);
        // OWNER 는 할 수 있다
        eventGrantService.delete(owner.getPublicId(), event.eventId(), otherGrant);
    }

    @Test
    @DisplayName("프로젝트 사진 권한: OPERATOR 를 ADMIN 으로 올려도 남고(PHOTO-001 아님), VIEWER·DESIGNER 로 내리면 사라진다")
    void projectPhotosSurvivePromotion() {
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        EventResponse event = ready("promote-1", project.projectId());
        projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(), EnumSet.of(PhotoPermission.VIEW_PHOTOS)));
        assertThat(photoPermissions(event)).containsExactly(PhotoPermission.VIEW_PHOTOS);

        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), operator.getPublicId(), WorkspaceRole.ADMIN);
        assertThat(photoPermissions(event)).containsExactly(PhotoPermission.VIEW_PHOTOS);
        assertThat(photoService.page(operator.getPublicId(), event.eventId(), null, null, null, null, null, null, null).items()).isEmpty();

        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), operator.getPublicId(), WorkspaceRole.VIEWER);
        assertThat(photoPermissions(event)).isEmpty();
        membershipService.changeRole(owner.getPublicId(), org.workspaceId(), operator.getPublicId(), WorkspaceRole.DESIGNER);
        assertThat(photoPermissions(event)).isEmpty();
    }

    private Set<PhotoPermission> photoPermissions(EventResponse event) {
        return accessPolicy.photoPermissions(eventRepository.findByPublicId(event.eventId()).orElseThrow(), operator.getId());
    }

    private EventResponse ready(String slug, String projectId) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(), settings(slug, projectId));
        return patch(owner, created, Map.of(
                "allowedLayouts", List.of("CLASSIC"),
                "frames", List.of(Map.of("frameVersionId", frameVersionId, "layoutType", "CLASSIC", "isDefault", true)),
                "consentItems", List.of(Map.of("code", "c", "version", 1, "title", "t", "required", true, "body", "b"))));
    }

    private static EventSettings settings(String slug, String projectId) {
        return new EventSettings("행사", slug, null, null, null, null, null, null, null, null, null, null, projectId);
    }

    private EventResponse patch(User actor, EventResponse event, Map<String, Object> settings) {
        int version = eventService.get(owner.getPublicId(), event.eventId()).settingsVersion();
        return eventService.patch(actor.getPublicId(), event.eventId(), new EventPatchRequest(version, objectMapper.valueToTree(settings)));
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
