package com.harucut.frame.workspace;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.dto.FrameVersionResponse;
import com.harucut.frame.dto.PickableFrameVersion;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import com.harucut.frame.service.FrameAdminService;
import com.harucut.frame.service.FrameService;
import com.harucut.frame.service.FrameVersionService;
import com.harucut.storage.dto.StoredObject;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/*
 * 조직 공간 프레임 — 공간의 것이다: 개인 목록·개인 한도에서 빠지고, 권한은 만든 사람이 아니라 공간 역할로 본다.
 * 여러 멤버가 한 프레임을 고쳐도(남이 넣은 이미지 포함) 다시 저장·발행이 막히지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("조직 공간 프레임 — 통합")
class WorkspaceFrameIntegrationTest {

    @Autowired private FrameService frameService;
    @Autowired private FrameVersionService frameVersionService;
    @Autowired private FrameAdminService frameAdminService;
    @Autowired private FrameRepository frameRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;

    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private com.harucut.audit.repository.AuditLogRepository auditLogRepository;
    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private User designer;
    private User viewer;
    private User stranger;
    private WorkspaceResponse org;

    @BeforeEach
    void setUp() throws IOException {
        byte[] png = png();
        given(fileStorageService.head(anyString())).willReturn(Optional.of(new StoredObject(png.length, "image/png")));
        given(fileStorageService.downloadBytes(anyString())).willReturn(png);
        given(fileStorageService.generatePresignedGetUrl(anyString())).willReturn("https://s3/get");

        owner = userRepository.save(User.localUser("owner@harucut.com", "e", "owner"));
        designer = userRepository.save(User.localUser("designer@harucut.com", "e", "designer"));
        viewer = userRepository.save(User.localUser("viewer@harucut.com", "e", "viewer"));
        stranger = userRepository.save(User.localUser("stranger@harucut.com", "e", "stranger"));
        org = workspaceService.createOrganization(owner.getPublicId(), "웨딩팀");
        Long orgId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        membershipRepository.save(WorkspaceMembership.member(orgId, designer.getId(), WorkspaceRole.DESIGNER));
        membershipRepository.save(WorkspaceMembership.member(orgId, viewer.getId(), WorkspaceRole.VIEWER));
    }

    @Test
    @DisplayName("조직 프레임은 개인 목록·개인 프레임 수에서 빠지고 공간 목록에 workspaceId 와 함께 나온다")
    void listedInWorkspaceNotPersonal() {
        FrameResponse orgFrame = create(owner, org.workspaceId(), photo(owner, "a.png"));
        FrameResponse personal = create(owner, null, photo(owner, "mine.png"));

        assertThat(frameService.getMyFrames(owner.getPublicId())).extracting(FrameResponse::frameId)
                .contains(personal.frameId()).doesNotContain(orgFrame.frameId());
        assertThat(frameRepository.countPersonalByUser(owner)).isEqualTo(1);
        assertThat(frameRepository.countPersonalByUserId(owner.getId())).isEqualTo(1);

        assertThat(frameService.getWorkspaceFrames(viewer.getPublicId(), org.workspaceId()))
                .singleElement()
                .satisfies(frame -> {
                    assertThat(frame.frameId()).isEqualTo(orgFrame.frameId());
                    assertThat(frame.workspaceId()).isEqualTo(org.workspaceId());
                });
        assertNotFound(() -> frameService.getWorkspaceFrames(stranger.getPublicId(), org.workspaceId()));
    }

    @Test
    @DisplayName("조회는 멤버 누구나, 수정·삭제·발행은 OWNER·ADMIN·DESIGNER — 다른 멤버는 403 GEN-021, 멤버가 아니면 404")
    void rolesGovernAccess() {
        FrameResponse frame = create(owner, org.workspaceId(), photo(owner, "a.png"));

        assertThat(frameService.getFrame(viewer.getPublicId(), frame.frameId()).frameId()).isEqualTo(frame.frameId());
        assertNotFound(() -> frameService.getFrame(stranger.getPublicId(), frame.frameId()));
        assertForbidden(() -> frameService.updateFrame(viewer.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), frame.version(), photo(owner, "a.png"))));
        assertForbidden(() -> frameVersionService.publish(viewer.getPublicId(), frame.frameId()));
        assertForbidden(() -> frameService.deleteFrame(viewer.getPublicId(), frame.frameId()));
        // 발행본 목록은 읽기 권한
        assertThat(frameVersionService.list(viewer.getPublicId(), frame.frameId())).isEmpty();

        frameService.deleteFrame(designer.getPublicId(), frame.frameId());
        assertThat(frameRepository.findById(frame.frameId())).isEmpty();
    }

    @Test
    @DisplayName("컴포넌트만 바꿔 저장해도 version 이 오른다(R5 L258, F16) — 다른 기기의 옛 version 저장은 FRAME-002")
    void componentOnlySaveBumpsVersion() {
        // 첫 저장으로 값 정규화를 끝낸 뒤 — 이 뒤의 저장은 컴포넌트 말고 바뀌는 칸이 없다
        FrameResponse created = create(owner, org.workspaceId(), photo(owner, "a.png"));
        FrameResponse frame = frameService.updateFrame(owner.getPublicId(), created.frameId(),
                request(owner, org.workspaceId(), created.version(), photo(owner, "a.png")));
        entityManager.flush();
        entityManager.clear();

        FrameResponse saved = frameService.updateFrame(owner.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), frame.version(), photo(owner, "a.png"), photo(owner, "b.png")));

        assertThat(saved.version()).isGreaterThan(frame.version());
        assertThatThrownBy(() -> frameService.updateFrame(owner.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), frame.version(), photo(owner, "c.png"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FrameErrorCode.VERSION_CONFLICT);
    }

    @Test
    @DisplayName("다른 멤버가 넣은 이미지가 있어도 DESIGNER 가 다시 저장·발행할 수 있다 — 남의(비멤버) key 는 여전히 403")
    void designerEditsOwnersFrame() {
        FrameResponse frame = create(owner, org.workspaceId(), photo(owner, "a.png"));

        // 만든 사람의 이미지(그대로) + 자기 이미지(새로) — 둘 다 통과
        FrameResponse saved = frameService.updateFrame(designer.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), frame.version(), photo(owner, "a.png"), photo(designer, "b.png")));
        assertThat(saved.components()).hasSize(2);

        // 만든 사람 폴더의 "새" key 는 프레임이 쓰던 것이 아니므로 막힌다
        assertThatThrownBy(() -> frameService.updateFrame(designer.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), saved.version(), photo(owner, "other.png"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> frameService.updateFrame(designer.getPublicId(), frame.frameId(),
                request(owner, org.workspaceId(), saved.version(), photo(stranger, "x.png"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.FORBIDDEN);

        // 만든 사람·자기 이미지가 섞인 프레임을 DESIGNER 가 발행한다
        assertThat(frameVersionService.publish(designer.getPublicId(), frame.frameId()).versionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("고르기 목록: 공간 프레임 먼저, 시스템 프레임 다음 — 최신 발행본 하나씩, 발행 안 된 프레임은 없다")
    void pickableVersions() {
        FrameResponse published = create(owner, org.workspaceId(), photo(owner, "a.png"));
        frameVersionService.publish(owner.getPublicId(), published.frameId());
        Long latest = frameVersionService.publish(owner.getPublicId(), published.frameId()).frameVersionId();
        create(owner, org.workspaceId(), photo(owner, "draft.png"));   // 발행 안 함
        FrameResponse other = create(stranger, null, photo(stranger, "s.png"));
        frameVersionService.publish(stranger.getPublicId(), other.frameId());   // 남의 개인 프레임 — 안 나온다
        FrameResponse system = frameAdminService.createSystemFrame(request(owner, null, null));
        Long systemVersion = frameVersionService.publishSystem(owner.getPublicId(), system.frameId()).frameVersionId();

        List<PickableFrameVersion> pickable = frameVersionService.pickable(viewer.getPublicId(), org.workspaceId());

        assertThat(pickable).extracting(PickableFrameVersion::frameVersionId).containsExactly(latest, systemVersion);
        assertThat(pickable.get(0).source()).isEqualTo(PickableFrameVersion.Source.WORKSPACE);
        assertThat(pickable.get(0).versionNo()).isEqualTo(2);
        assertThat(pickable.get(0).frameType()).isEqualTo(FrameType.CLASSIC);
        assertThat(pickable.get(1).source()).isEqualTo(PickableFrameVersion.Source.SYSTEM);
        assertNotFound(() -> frameVersionService.pickable(stranger.getPublicId(), org.workspaceId()));
    }

    @Test
    @DisplayName("긴급 회수: 고칠 수 있는 역할만, 처음 사유가 남고 감사는 한 번, 최신 발행본이 회수되면 고르기 목록에서 빠지고 이전 발행본으로 내려가지 않는다")
    void revokeVersion() {
        FrameResponse frame = create(owner, org.workspaceId(), photo(owner, "a.png"));
        frameVersionService.publish(owner.getPublicId(), frame.frameId());
        Long latest = frameVersionService.publish(owner.getPublicId(), frame.frameId()).frameVersionId();
        FrameResponse system = frameAdminService.createSystemFrame(request(owner, null, null));
        Long systemVersion = frameVersionService.publishSystem(owner.getPublicId(), system.frameId()).frameVersionId();

        assertThatThrownBy(() -> frameVersionService.revoke(viewer.getPublicId(), frame.frameId(), 2, "x"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(GlobalErrorCode.FORBIDDEN);
        assertNotFound(() -> frameVersionService.revoke(stranger.getPublicId(), frame.frameId(), 2, "x"));
        assertNotFound(() -> frameVersionService.revoke(owner.getPublicId(), frame.frameId(), 9, "x"));
        // 시스템 프레임은 사용자 경로로 못 건드린다
        assertNotFound(() -> frameVersionService.revoke(owner.getPublicId(), system.frameId(), 1, "x"));

        FrameVersionResponse revoked = frameVersionService.revoke(designer.getPublicId(), frame.frameId(), 2, "저작권 문의");
        assertThat(revoked.frameVersionId()).isEqualTo(latest);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(frameVersionService.revoke(owner.getPublicId(), frame.frameId(), 2, "다른 사유").revokedReason()).isEqualTo("저작권 문의");
        assertThat(frameVersionService.list(viewer.getPublicId(), frame.frameId())).extracting(FrameVersionResponse::revokedReason)
                .containsExactly("저작권 문의", null);
        assertThat(auditLogRepository.findAll()).filteredOn(log -> log.getAction().equals("FRAME_VERSION_REVOKED")).singleElement()
                .satisfies(log -> {
                    assertThat(log.getActorUserId()).isEqualTo(designer.getId());
                    assertThat(log.getTargetId()).isEqualTo(latest.toString());
                    assertThat(log.getDetail()).contains("저작권 문의");
                });
        assertThat(frameVersionService.pickable(viewer.getPublicId(), org.workspaceId()))
                .extracting(PickableFrameVersion::frameVersionId).containsExactly(systemVersion);

        // 관리자 경로 — 시스템 발행본도 id 로
        assertThat(frameVersionService.revokeByAdmin(owner.getPublicId(), systemVersion, "라이선스 만료").revokedAt()).isNotNull();
        assertThat(frameVersionService.pickable(viewer.getPublicId(), org.workspaceId())).isEmpty();
        assertThat(frameVersionService.restoreByAdmin(owner.getPublicId(), systemVersion).revokedAt()).isNull();
        assertNotFound(() -> frameVersionService.revokeByAdmin(owner.getPublicId(), 999_999L, "x"));

        // 시스템 프레임 발행본 목록 — 회수 상태까지. 시스템 프레임이 아니면 FRAME-001
        frameVersionService.revokeByAdmin(owner.getPublicId(), systemVersion, "라이선스 만료");
        assertThat(frameVersionService.listSystem(system.frameId())).singleElement()
                .satisfies(version -> assertThat(version.revokedReason()).isEqualTo("라이선스 만료"));
        frameVersionService.restoreByAdmin(owner.getPublicId(), systemVersion);
        assertThatThrownBy(() -> frameVersionService.listSystem(frame.frameId()))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(com.harucut.frame.exception.FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);

        // 해제하면 사유도 지워지고 고르기 목록으로 돌아온다
        assertThat(frameVersionService.restore(designer.getPublicId(), frame.frameId(), 2).revokedReason()).isNull();
        assertThat(frameVersionService.pickable(viewer.getPublicId(), org.workspaceId()))
                .extracting(PickableFrameVersion::frameVersionId).containsExactly(latest, systemVersion);
        assertThat(frameVersionService.restore(designer.getPublicId(), frame.frameId(), 2).revokedAt()).isNull();
        assertThat(auditLogRepository.findAll()).filteredOn(log -> log.getAction().equals("FRAME_VERSION_RESTORED")).hasSize(3);
    }

    // ── fixtures ──────────────────────────────

    private FrameResponse create(User user, String workspaceId, FrameCreateRequest.ComponentRequest... components) {
        return frameService.createFrame(user.getPublicId(), request(user, workspaceId, null, components));
    }

    // 프리뷰는 만든 사람 폴더 — 다른 멤버가 다시 저장할 때도 같은 key 를 되돌려 보낸다(프레임이 쓰던 key)
    private FrameCreateRequest request(User previewOwner, String workspaceId, Long version,
                                       FrameCreateRequest.ComponentRequest... components) {
        return new FrameCreateRequest("프레임", null, S3Keys.userRoot(previewOwner.getPublicId()) + "frames/p.png",
                FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Color("#FFFFFF"), null, List.of(components), version, workspaceId);
    }

    private static FrameCreateRequest.ComponentRequest photo(User user, String name) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.PHOTO,
                S3Keys.userRoot(user.getPublicId()) + "components/" + name, null,
                0.0, 0.0, 100.0, 100.0, 1.0, 0.0, 0, null);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.FORBIDDEN);
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(GlobalErrorCode.NOT_FOUND);
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
