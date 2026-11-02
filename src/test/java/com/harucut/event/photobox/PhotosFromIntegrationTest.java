package com.harucut.event.photobox;

import com.harucut.audit.repository.AuditLogRepository;
import com.harucut.auth.cookie.CookieManager;
import com.harucut.auth.service.JwtTokenService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.dto.PhotoDtos;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventGrantService;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.exception.ExportErrorCode;
import com.harucut.export.service.ExportService;
import com.harucut.export.service.MultiExportService;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserRole;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.MemberDtos;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.exception.MemberErrorCode;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.InvitationService;
import com.harucut.workspace.service.MembershipService;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 새 수신자의 과거 사진 제한(A16, R5 L347, F27) — 수신자를 더해도 이미 제출된 사진의 열람 범위가 저절로 넓어지지 않는다.
 * 기본은 수락·부여 뒤 사진만이고, 같은 처리 주체 안의 담당자 교체면 운영자가 includePast 로 연다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("사진 범위(photosFrom) — 통합")
class PhotosFromIntegrationTest {

    private static final Set<PhotoPermission> ALL = PhotoPermission.all();

    @Autowired private MockMvcTester mvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PhotoService photoService;
    @Autowired private ExportService exportService;
    @Autowired private MultiExportService multiExportService;
    @Autowired private EventService eventService;
    @Autowired private EventGrantService eventGrantService;
    @Autowired private InvitationService invitationService;
    @Autowired private MembershipService membershipService;
    @Autowired private ProjectService projectService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private User owner;
    private User customer;
    private WorkspaceResponse org;
    private Long workspaceId;
    private Event event;
    private Participant participant;
    private ComposeJob past;

    @BeforeEach
    void setUp() {
        given(fileStorageService.generatePresignedGetUrl(anyString(), any())).willReturn("https://s3/thumb");
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/download");

        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        customer = userRepository.save(User.localUser("customer@harucut.com", "e", "신부"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "photos-from", null, null, null, null, null, null, null, null, null, null, null, null));
        event = eventRepository.findByPublicId(created.eventId()).orElseThrow();
        participant = participantRepository.save(Participant.byod(event.getId()));
        past = done("past");
        entityManager.flush();
        jdbcTemplate.update("update compose_job set created_at = ? where compose_job_id = ?", LocalDateTime.now().minusHours(1), past.getId());
        entityManager.clear();
    }

    @Test
    @DisplayName("기본 — 수락한 뒤 사진만: 목록·다운로드·삭제·링크 회수·사진 수 모두, from 을 앞당겨도 넓어지지 않는다. 운영자는 그대로 다 본다")
    void acceptedRecipientSeesOnlyLaterPhotos() {
        MemberDtos.InvitationAccepted accepted = accept(customer, ALL, null);
        assertThat(accepted.photosFrom()).isNotNull();
        ComposeJob later = done("later");

        assertThat(page(customer, null)).containsExactly(later.getId());
        assertThat(page(customer, OffsetDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(1))).containsExactly(later.getId());
        assertThat(photoService.download(customer.getPublicId(), event.getPublicId(), later.getId()).url()).isEqualTo("https://s3/download");
        assertCode(() -> photoService.download(customer.getPublicId(), event.getPublicId(), past.getId()), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> photoService.delete(customer.getPublicId(), event.getPublicId(), past.getId()), GlobalErrorCode.NOT_FOUND);
        assertCode(() -> photoService.revokeResultAccess(customer.getPublicId(), event.getPublicId(), past.getId(), "오발송"), GlobalErrorCode.NOT_FOUND);

        MemberDtos.MyEvent mine = eventGrantService.myEvents(customer.getPublicId()).get(0);
        assertThat(mine.access().photosFrom()).isNotNull();
        assertThat(mine.completedPhotoCount()).isEqualTo(1);
        assertThat(grantOf(customer).photosFrom()).isNotNull();

        assertThat(page(owner, null)).containsExactlyInAnyOrder(past.getId(), later.getId());
        MemberDtos.MyEvent ownerRow = eventGrantService.myEvents(owner.getPublicId()).get(0);
        assertThat(ownerRow.access().photosFrom()).isNull();
        assertThat(ownerRow.completedPhotoCount()).isEqualTo(2);
        assertThat(grantOf(owner).photosFrom()).isNull();
    }

    @Test
    @DisplayName("묶음도 범위 안에서만: 고른 사진이 전부 범위 밖이면 EXPORT-003, 전체·기간 필터는 범위 안만. 여러 행사 묶음도 같다")
    void exportsStayInScope() {
        accept(customer, ALL, null);
        ComposeJob later = done("later");

        assertCode(() -> exportService.create(customer.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(past.getId()), null)), ExportErrorCode.INVALID_SELECTION);
        assertThat(exportService.create(customer.getPublicId(), event.getPublicId(),
                new ExportDtos.ExportRequest(List.of(past.getId(), later.getId()), null)).photoCount()).isEqualTo(1);
        assertThat(exportService.create(customer.getPublicId(), event.getPublicId(), new ExportDtos.ExportRequest(null, null)).photoCount()).isEqualTo(1);
        assertThat(exportService.create(customer.getPublicId(), event.getPublicId(), new ExportDtos.ExportRequest(null,
                new ExportDtos.ExportFilter(OffsetDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(1), null, null, null))).photoCount()).isEqualTo(1);

        User admin = userRepository.save(User.localUser("admin@harucut.com", "e", "관리자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, admin.getId(), WorkspaceRole.ADMIN));
        accept(admin, ALL, null);
        assertCode(() -> multiExportService.create(admin.getPublicId(), ExportScope.WORKSPACE, org.workspaceId(),
                new ExportDtos.MultiExportRequest(List.of(event.getPublicId()))), ExportErrorCode.INVALID_SELECTION);
        done("after-admin");
        assertThat(multiExportService.create(admin.getPublicId(), ExportScope.WORKSPACE, org.workspaceId(),
                new ExportDtos.MultiExportRequest(List.of(event.getPublicId()))).photoCount()).isEqualTo(1);
        assertThat(multiExportService.create(owner.getPublicId(), ExportScope.WORKSPACE, org.workspaceId(),
                new ExportDtos.MultiExportRequest(List.of(event.getPublicId()))).photoCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("includePast: 미리보기·열린 초대에 알리고, 수락하면 제한 없이 과거 사진까지 본다. 감사에 남는다. 기본 초대의 미리보기는 false")
    void includePastOpensPastPhotos() {
        MemberDtos.InvitationIssued open = invite(ALL, true);
        assertThat(invitationService.preview(customer.getPublicId(), open.inviteToken()).includePast()).isTrue();
        assertThat(membershipService.invitations(owner.getPublicId(), org.workspaceId())).singleElement()
                .satisfies(invitation -> assertThat(invitation.includePast()).isTrue());
        assertThat(invitationService.preview(customer.getPublicId(), invite(ALL, null).inviteToken()).includePast()).isFalse();

        MemberDtos.InvitationAccepted accepted = invitationService.accept(customer.getPublicId(), open.inviteToken());

        assertThat(accepted.photosFrom()).isNull();
        assertThat(page(customer, null)).containsExactly(past.getId());
        assertThat(eventGrantService.myEvents(customer.getPublicId()).get(0).access().photosFrom()).isNull();
        assertThat(auditLogRepository.findAll()).filteredOn(log -> log.getAction().equals("CUSTOMER_INVITED"))
                .anySatisfy(log -> assertThat(log.getDetail()).contains("\"includePast\":true"));
    }

    @Test
    @DisplayName("나중에 열기: 권한 수정 includePast·제한을 푸는 새 초대는 열고, 그 밖의 수정·재수락은 범위를 바꾸지 않는다. 연 범위는 다시 줄지 않는다")
    void laterChanges() {
        accept(customer, Set.of(PhotoPermission.VIEW_PHOTOS), null);
        OffsetDateTime from = grantOf(customer).photosFrom();
        Long grantId = grantOf(customer).grantId();

        // 같은 권한의 기본 초대는 이미 받은 것이다
        assertCode(() -> accept(customer, Set.of(PhotoPermission.VIEW_PHOTOS), null), MemberErrorCode.ALREADY_MEMBER);
        // 권한을 더해도 범위는 그대로다
        accept(customer, Set.of(PhotoPermission.VIEW_PHOTOS, PhotoPermission.DOWNLOAD_PHOTOS), false);
        assertThat(grantOf(customer).photosFrom()).isEqualTo(from);
        assertThat(eventGrantService.update(owner.getPublicId(), event.getPublicId(), grantId, Set.of(PhotoPermission.VIEW_PHOTOS), false).photosFrom())
                .isEqualTo(from);
        assertThat(page(customer, null)).isEmpty();

        assertThat(eventGrantService.update(owner.getPublicId(), event.getPublicId(), grantId, Set.of(PhotoPermission.VIEW_PHOTOS), true).photosFrom()).isNull();
        assertThat(page(customer, null)).containsExactly(past.getId());
        assertThat(eventGrantService.update(owner.getPublicId(), event.getPublicId(), grantId, ALL, false).photosFrom()).isNull();
        assertThat(auditLogRepository.findAll()).filteredOn(log -> log.getAction().equals("GRANT_CHANGED"))
                .anySatisfy(log -> assertThat(log.getDetail()).contains("\"includePast\":true"));

        // 권한이 같아도 제한을 푸는 초대는 받는다
        User other = userRepository.save(User.localUser("other@harucut.com", "e", "담당자"));
        accept(other, Set.of(PhotoPermission.VIEW_PHOTOS), null);
        assertThat(accept(other, Set.of(PhotoPermission.VIEW_PHOTOS), true).photosFrom()).isNull();
        assertThat(page(other, null)).containsExactly(past.getId());
    }

    @Test
    @DisplayName("운영 권한만 있던 행에 처음 사진 권한을 주면 그 시각부터다. 범위가 제한된 OPERATOR 는 includePast 를 주지 못한다")
    void operators() {
        User operator = userRepository.save(User.localUser("operator@harucut.com", "e", "운영자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        MemberDtos.EventGrantView assigned = eventGrantService.assignOperator(owner.getPublicId(), event.getPublicId(), operator.getPublicId(),
                Set.of(OperationPermission.INVITE_CUSTOMERS));
        assertThat(assigned.photosFrom()).isNull();

        MemberDtos.EventGrantView granted = eventGrantService.update(owner.getPublicId(), event.getPublicId(), assigned.grantId(),
                Set.of(PhotoPermission.VIEW_PHOTOS), false);
        assertThat(granted.photosFrom()).isNotNull();
        assertThat(page(operator, null)).isEmpty();

        assertCode(() -> eventGrantService.inviteCustomer(operator.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null, true)), GlobalErrorCode.FORBIDDEN);
        assertThat(eventGrantService.inviteCustomer(operator.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(Set.of(PhotoPermission.VIEW_PHOTOS), null)).inviteToken()).isNotBlank();
    }

    @Test
    @DisplayName("프로젝트 멤버(내부 담당자)의 사진 권한이 있으면 제한이 없다")
    void projectMemberIsNotLimited() {
        User operator = userRepository.save(User.localUser("operator@harucut.com", "e", "운영자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        ProjectDtos.Project project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄", null));
        jdbcTemplate.update("update event set project_id = (select project_id from project where public_id = ?) where event_id = ?",
                project.projectId(), event.getId());
        entityManager.clear();
        accept(operator, Set.of(PhotoPermission.VIEW_PHOTOS), null);
        assertThat(page(operator, null)).isEmpty();

        projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(EnumSet.noneOf(OperationPermission.class), Set.of(PhotoPermission.VIEW_PHOTOS)));

        assertThat(page(operator, null)).containsExactly(past.getId());
        assertThat(eventGrantService.myEvents(operator.getPublicId()).get(0).access().photosFrom()).isNull();
    }

    @Test
    @DisplayName("사용량 — 범위가 제한된 수신자는 403(숨긴 사진 수를 짐작하지 못하게), 제한이 없는 고객은 그대로")
    void usage() {
        accept(customer, Set.of(PhotoPermission.VIEW_PHOTOS), null);
        assertThat(mvc.get().uri("/api/auth/events/{id}/usage", event.getPublicId()).cookie(cookie(customer)))
                .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("GEN-021");

        eventGrantService.update(owner.getPublicId(), event.getPublicId(), grantOf(customer).grantId(), Set.of(PhotoPermission.VIEW_PHOTOS), true);
        entityManager.flush();
        assertThat(mvc.get().uri("/api/auth/events/{id}/usage", event.getPublicId()).cookie(cookie(customer))).hasStatusOk();
    }

    private MemberDtos.InvitationIssued invite(Set<PhotoPermission> permissions, Boolean includePast) {
        return eventGrantService.inviteCustomer(owner.getPublicId(), event.getPublicId(),
                new MemberDtos.EventInvitationRequest(permissions, null, includePast));
    }

    private MemberDtos.InvitationAccepted accept(User user, Set<PhotoPermission> permissions, Boolean includePast) {
        return invitationService.accept(user.getPublicId(), invite(permissions, includePast).inviteToken());
    }

    private MemberDtos.EventGrantView grantOf(User user) {
        return eventGrantService.grants(owner.getPublicId(), event.getPublicId()).stream()
                .filter(grant -> grant.userId().equals(user.getPublicId())).findFirst().orElseThrow();
    }

    private List<Long> page(User user, OffsetDateTime from) {
        return photoService.page(user.getPublicId(), event.getPublicId(), null, null, from, null, null, null, null).items().stream()
                .map(PhotoDtos.Photo::photoId).toList();
    }

    private ComposeJob done(String key) {
        ComposeJob job = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, 41L));
        composeJobRepository.markDone(job.getId(), "uploads/events/e/results/" + key + ".jpg", ComposeStatus.PENDING, ComposeStatus.DONE);
        // 상태 전이는 벌크 UPDATE 다 — 운영에서는 전이가 다른 트랜잭션이라 다음 요청이 새로 읽는다
        entityManager.flush();
        entityManager.clear();
        return job;
    }

    private Cookie cookie(User user) {
        return new Cookie(CookieManager.ACCESS_TOKEN,
                jwtTokenService.createAccessToken(user.getPublicId(), UserRole.ROLE_USER, UserStatus.ACTIVE).value());
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
