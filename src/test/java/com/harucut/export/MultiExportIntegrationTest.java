package com.harucut.export;

import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.ErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.entitlement.entity.Contract;
import com.harucut.entitlement.entity.Entitlement;
import com.harucut.entitlement.exception.EntitlementErrorCode;
import com.harucut.entitlement.repository.EntitlementRepository;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.entity.Event;
import com.harucut.event.enums.OperationPermission;
import com.harucut.event.enums.PhotoPermission;
import com.harucut.event.exception.PhotoErrorCode;
import com.harucut.event.repository.EventGrantRepository;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventService;
import com.harucut.event.service.PhotoService;
import com.harucut.export.dto.ExportDtos;
import com.harucut.export.enums.ExportScope;
import com.harucut.export.enums.ExportStatus;
import com.harucut.export.exception.ExportErrorCode;
import com.harucut.export.repository.ExportJobRepository;
import com.harucut.export.service.ExportService;
import com.harucut.export.service.ExportWorker;
import com.harucut.export.service.MultiExportService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.FrameType;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.participant.entity.Participant;
import com.harucut.participant.repository.ParticipantRepository;
import com.harucut.project.dto.ProjectDtos;
import com.harucut.project.service.ProjectService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.service.S3MultipartOutputStream;
import com.harucut.support.TestEntitlements;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/*
 * P3-5 — 여러 행사 묶음 다운로드(프로젝트·공간 범위, ADR-0009 §6). 압축은 행사 묶음과 같은 작업자다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("여러 행사 묶음 다운로드 — 통합")
class MultiExportIntegrationTest {

    @Autowired private MultiExportService multiExportService;
    @Autowired private ExportService exportService;
    @Autowired private ExportWorker exportWorker;
    @Autowired private ExportJobRepository exportJobRepository;
    @Autowired private ProjectService projectService;
    @Autowired private EventService eventService;
    @Autowired private PhotoService photoService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventGrantRepository grantRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private ComposeJobRepository composeJobRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;

    private final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
    private User owner;
    private WorkspaceResponse org;
    private Long workspaceId;
    private ProjectDtos.Project project;
    private Event a;
    private Event b;
    private Event outside;
    private ComposeJob a1;
    private ComposeJob a2;
    private ComposeJob b1;

    @BeforeEach
    void setUp() {
        S3Client s3Client = mock(S3Client.class);
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).willAnswer(invocation -> {
            uploaded.write(((RequestBody) invocation.getArgument(1)).contentStreamProvider().newStream().readAllBytes());
            return UploadPartResponse.builder().eTag("e").build();
        });
        given(fileStorageService.openMultipartUpload(anyString(), anyString()))
                .willAnswer(invocation -> new S3MultipartOutputStream(s3Client, "bucket", invocation.getArgument(0), "application/zip", 1024));
        given(fileStorageService.openStream(anyString()))
                .willAnswer(invocation -> new ByteArrayInputStream(("photo:" + invocation.getArgument(0)).getBytes()));
        given(fileStorageService.generatePresignedDownloadUrl(anyString(), anyString(), any())).willReturn("https://s3/zip");

        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩홀");
        workspaceId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        TestEntitlements.grant(entitlementRepository, workspaceId);
        project = projectService.create(owner.getPublicId(), org.workspaceId(), new ProjectDtos.CreateRequest("봄 시즌", null));
        a = event("multi-a", project.projectId());
        b = event("multi-b", project.projectId());
        outside = event("multi-c", null);
        a1 = done(a, "a1", "uploads/events/a/results/1.jpg");
        a2 = done(a, "a2", "uploads/events/a/results/2.jpg");
        b1 = done(b, "b1", "uploads/events/b/results/1.jpg");
        done(outside, "c1", "uploads/events/c/results/1.jpg");
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("프로젝트 묶음: 행사마다 보이는 사진 전부, 응답 scope·events, ZIP 은 {slug}/번호, 받으면 「{프로젝트명}-{날짜}.zip」, 목록은 최신순")
    void projectExport() throws IOException {
        ExportDtos.Export export = multiExportService.create(owner.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), b.getPublicId())));
        assertThat(export.scope()).isEqualTo(ExportScope.PROJECT);
        assertThat(export.photoCount()).isEqualTo(3);
        assertThat(export.events()).extracting(ExportDtos.ExportEvent::eventId, ExportDtos.ExportEvent::photoCount)
                .containsExactlyInAnyOrder(tuple(a.getPublicId(), 2), tuple(b.getPublicId(), 1));
        assertCode(() -> download(export), ExportErrorCode.NOT_READY);

        exportWorker.run(id(export));

        assertThat(multiExportService.get(owner.getPublicId(), ExportScope.PROJECT, project.projectId(), export.exportId()).status())
                .isEqualTo(ExportStatus.DONE);
        // 행사 안은 행사 묶음과 같은 순서(최신 먼저), 폴더마다 번호를 새로 센다
        assertThat(unzip(uploaded.toByteArray()).keySet()).containsExactly(
                "multi-a/0001-" + a2.getId() + ".jpg", "multi-a/0002-" + a1.getId() + ".jpg", "multi-b/0001-" + b1.getId() + ".jpg");
        assertThat(download(export).url()).isEqualTo("https://s3/zip");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        then(fileStorageService).should().generatePresignedDownloadUrl(anyString(), eq("봄 시즌-" + today + ".zip"), any());

        ExportDtos.Export later = multiExportService.create(owner.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(b.getPublicId())));
        assertThat(multiExportService.mine(owner.getPublicId(), ExportScope.PROJECT, project.projectId()))
                .extracting(ExportDtos.Export::exportId).containsExactly(later.exportId(), export.exportId());
        // 프로젝트 밖 행사는 GEN-002, 행사 묶음 API 로는 보이지 않는다
        assertCode(() -> multiExportService.create(owner.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), outside.getPublicId()))), GlobalErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> exportService.get(owner.getPublicId(), a.getPublicId(), export.exportId()), GlobalErrorCode.NOT_FOUND);
        assertThat(exportService.get(owner.getPublicId(), a.getPublicId(),
                exportService.create(owner.getPublicId(), a.getPublicId(), new ExportDtos.ExportRequest(null, null)).exportId()).scope()).isNull();
    }

    @Test
    @DisplayName("공간 묶음: 프로젝트 밖 행사도 담는다, OWNER·ADMIN 만(운영 담당 403), 프로젝트 멤버가 아니면 프로젝트 묶음 404")
    void workspaceExportAndAccess() {
        ExportDtos.Export export = multiExportService.create(owner.getPublicId(), ExportScope.WORKSPACE, org.workspaceId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), outside.getPublicId())));
        assertThat(export.scope()).isEqualTo(ExportScope.WORKSPACE);
        assertThat(export.photoCount()).isEqualTo(3);

        User operator = userRepository.save(User.localUser("op@harucut.com", "e", "운영 담당"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        assertCode(() -> multiExportService.mine(operator.getPublicId(), ExportScope.WORKSPACE, org.workspaceId()), GlobalErrorCode.FORBIDDEN);
        assertCode(() -> multiExportService.mine(operator.getPublicId(), ExportScope.PROJECT, project.projectId()), GlobalErrorCode.NOT_FOUND);
        // 다른 사람의 묶음은 같은 공간의 관리자에게도 없는 것과 같다
        User admin = userRepository.save(User.localUser("admin@harucut.com", "e", "관리자"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, admin.getId(), WorkspaceRole.ADMIN));
        assertCode(() -> multiExportService.get(admin.getPublicId(), ExportScope.WORKSPACE, org.workspaceId(), export.exportId()),
                GlobalErrorCode.NOT_FOUND);
        assertThat(multiExportService.mine(admin.getPublicId(), ExportScope.WORKSPACE, org.workspaceId())).isEmpty();
        // 공간 묶음은 프로젝트 경로로 보이지 않는다
        assertCode(() -> multiExportService.get(owner.getPublicId(), ExportScope.PROJECT, project.projectId(), export.exportId()),
                GlobalErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("권한은 행사마다: 묶음 권한이 없는 행사가 있으면 PHOTO-001(data.eventIds), 이용권 기능이 없으면 ENT-004(data.eventIds) — 조용히 빼지 않는다")
    void permissionsPerEvent() {
        User operator = userRepository.save(User.localUser("op@harucut.com", "e", "운영 담당"));
        membershipRepository.save(WorkspaceMembership.member(workspaceId, operator.getId(), WorkspaceRole.OPERATOR));
        projectService.putMember(owner.getPublicId(), project.projectId(), operator.getPublicId(),
                new ProjectDtos.MemberRequest(Set.of(), EnumSet.of(PhotoPermission.VIEW_PHOTOS)));
        assertThatThrownBy(() -> multiExportService.create(operator.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), b.getPublicId()))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PhotoErrorCode.PERMISSION_REQUIRED);
                    assertThat(e.getData()).isEqualTo(Map.of("eventIds", List.of(a.getPublicId(), b.getPublicId())));
                });

        // 행사 B 에만 BULK_DOWNLOAD 가 없는 행사 범위 이용권 — 그 행사만 ENT-004
        LocalDateTime now = LocalDateTime.now();
        entitlementRepository.save(Entitlement.manual(workspaceId, b.getId(), new Contract.Terms(10, null, null, null, Set.of()),
                now.minusDays(1), now.plusDays(10), null, "기능 없음"));
        assertThatThrownBy(() -> multiExportService.create(owner.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), b.getPublicId()))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(EntitlementErrorCode.FEATURE_NOT_ENTITLED);
                    assertThat(e.getData()).isEqualTo(Map.of("eventIds", List.of(b.getPublicId())));
                });
    }

    @Test
    @DisplayName("받기 재검사: 담긴 행사의 묶음 권한이 빠지면 PHOTO-001, 담긴 사진이 지워지면 410 EXPORT-002(그 자리에서 EXPIRED)")
    void downloadRechecks() {
        ExportDtos.Export export = multiExportService.create(owner.getPublicId(), ExportScope.PROJECT, project.projectId(),
                new ExportDtos.MultiExportRequest(List.of(a.getPublicId(), b.getPublicId())));
        exportWorker.run(id(export));

        var grant = grantRepository.findByEventIdAndUserId(b.getId(), owner.getId()).orElseThrow();
        grant.replace(EnumSet.of(PhotoPermission.VIEW_PHOTOS));
        entityManager.flush();
        assertCode(() -> download(export), PhotoErrorCode.PERMISSION_REQUIRED);
        grant.replace(PhotoPermission.all());
        entityManager.flush();

        photoService.delete(owner.getPublicId(), a.getPublicId(), a1.getId());
        assertCode(() -> download(export), ExportErrorCode.GONE);
        assertThat(exportJobRepository.findById(id(export)).orElseThrow().getStatus()).isEqualTo(ExportStatus.EXPIRED);
    }

    private Event event(String slug, String projectId) {
        EventResponse created = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings(slug, slug, null, null, null, null, null, null, null, null, null, null, projectId, null));
        return eventRepository.findByPublicId(created.eventId()).orElseThrow();
    }

    private ComposeJob done(Event event, String key, String resultKey) {
        Participant participant = participantRepository.save(Participant.byod(event.getId()));
        ComposeJob job = composeJobRepository.save(ComposeJob.forParticipant(participant.getId(), "uploads/events/e/", 1L, key,
                List.of("a", "b", "c", "d"), new ComposeSpec(2000, 6000, new BackgroundAttributes.Color("#FFF"),
                        FrameType.CLASSIC.getLayout().slots(), List.of(false, false, false, false), List.of()),
                event.getId(), null, null, 41L));
        composeJobRepository.markDone(job.getId(), resultKey, ComposeStatus.PENDING, ComposeStatus.DONE);
        return job;
    }

    private com.harucut.result.dto.DownloadUrlResponse download(ExportDtos.Export export) {
        return multiExportService.download(owner.getPublicId(), ExportScope.PROJECT, project.projectId(), export.exportId());
    }

    private Long id(ExportDtos.Export export) {
        return exportJobRepository.findByPublicId(export.exportId()).orElseThrow().getId();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes()));
            }
        }
        return entries;
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
