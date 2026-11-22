package com.harucut.support.mysql;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.enums.AssetPurpose;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.auth.service.RefreshTokenRepository;
import com.harucut.auth.service.UserExitService;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.service.FrameService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.storage.service.S3Deleter;
import com.harucut.storage.util.S3Keys;
import com.harucut.user.entity.User;
import com.harucut.user.enums.UserStatus;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.entity.WorkspaceMembership;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.repository.WorkspaceMembershipRepository;
import com.harucut.workspace.repository.WorkspaceRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;

/*
 * 탈퇴의 자산 정리를 실제 MySQL(Flyway 스키마의 FK) 위에서 돌린다. H2 스키마에는 frame_component·frame_version_asset → asset FK 가
 * 없어(엔티티가 id 를 값으로 들고 있다) 이 실패가 재현되지 않는다. 테스트 트랜잭션 없이 돌려 탈퇴 트랜잭션이 실제로 커밋되는지 본다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@ActiveProfiles("test")
@DisplayName("탈퇴 — 조직 프레임이 쓰는 개인 자산, 실제 MySQL")
class UserExitAssetsMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private UserExitService userExitService;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMembershipRepository membershipRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private FrameService frameService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoBean private S3Deleter s3Deleter;
    @MockitoBean private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("개인 공간에 올린 자산을 조직 프레임에 썼어도 탈퇴가 끝난다 — 그 자산은 조직 공간으로 옮겨 남고, 아무도 안 쓰는 개인 자산만 지운다")
    void exitKeepsPersonalAssetsUsedByOrganizationFrames() {
        User leaver = userRepository.save(User.localUser("leaver@exit-assets.test", "e", "떠남"));
        User partner = userRepository.save(User.localUser("partner@exit-assets.test", "e", "남음"));
        Long personalId = workspaceService.personalWorkspaceOf(leaver.getId()).getId();
        WorkspaceResponse org = workspaceService.createOrganization(partner.getPublicId(), "남는 조직");
        Long orgId = workspaceRepository.findByPublicId(org.workspaceId()).orElseThrow().getId();
        membershipRepository.save(WorkspaceMembership.member(orgId, leaver.getId(), WorkspaceRole.ADMIN));

        Asset sticker = verifiedPersonal(leaver, personalId, "sticker.png");   // 조직 프레임 컴포넌트가 id 로 쓴다(FK)
        Asset background = verifiedPersonal(leaver, personalId, "bg.png");     // 조직 프레임 배경이 key 로 쓴다(FK 없음)
        Asset unused = verifiedPersonal(leaver, personalId, "unused.png");     // 아무도 쓰지 않는다

        // 올린 사람은 자기 자산을 공간과 상관없이 쓸 수 있다(FrameAssetResolver.checkUsable)
        frameService.createFrame(leaver.getPublicId(), new FrameCreateRequest("조직 프레임", null,
                S3Keys.userRoot(leaver.getPublicId()) + "frames/p.png", FrameType.CLASSIC, null, null,
                new BackgroundAttributes.Image(background.getS3Key(), 1.0, null), null,
                List.of(component(sticker.getPublicId())), null, org.workspaceId()));

        userExitService.requestExit(leaver.getPublicId());
        userExitService.exit(leaver.getId());

        assertThat(userRepository.findById(leaver.getId()).orElseThrow().getUserStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(workspaceIdOf(sticker)).isEqualTo(orgId);
        assertThat(workspaceIdOf(background)).isEqualTo(orgId);
        assertThat(assetRepository.findById(unused.getId())).isEmpty();
        assertThat(scheduledForDeletion()).contains(unused.getS3Key())
                .doesNotContain(sticker.getS3Key(), background.getS3Key());
    }

    private Asset verifiedPersonal(User owner, Long personalId, String name) {
        Asset asset = Asset.pendingUpload(Asset.newPublicId(), personalId, AssetPurpose.FRAME_ASSET,
                S3Keys.userRoot(owner.getPublicId()) + "assets/" + name, "image/png", 100, owner.getId());
        asset.verify(100, 10, 10, LocalDateTime.now());
        return assetRepository.save(asset);
    }

    private Long workspaceIdOf(Asset asset) {
        return jdbcTemplate.queryForObject("select workspace_id from asset where asset_id = ?", Long.class, asset.getId());
    }

    private static FrameCreateRequest.ComponentRequest component(String assetId) {
        return new FrameCreateRequest.ComponentRequest(null, ComponentType.PHOTO, "x", null,
                0.0, 0.0, 10.0, 10.0, 1.0, 0.0, 0, null, assetId, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<String> scheduledForDeletion() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        then(s3Deleter).should(atLeast(0)).deleteAfterCommit(captor.capture());
        List<String> keys = new ArrayList<>();
        captor.getAllValues().forEach(keys::addAll);
        return keys;
    }
}
