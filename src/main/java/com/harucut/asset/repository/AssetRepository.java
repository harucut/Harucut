package com.harucut.asset.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import com.harucut.asset.entity.Asset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByPublicId(String publicId);

    Optional<Asset> findByS3Key(String s3Key);

    List<Asset> findAllByPublicIdIn(Collection<String> publicIds);

    /**
     * 촬영 입력을 job 에 묶는다 — 아직 아무 job 에도 묶이지 않았을 때만. 영향 행 수가 묶은 개수다.
     * 동시에 두 job 이 같은 입력을 쓰려 하면 한쪽만 이긴다
     */
    @Modifying(flushAutomatically = true)
    @Query("update Asset a set a.boundComposeJobId = :jobId where a.id in :ids and a.boundComposeJobId is null")
    int bindToJob(Collection<Long> ids, Long jobId);

    // 작업에 묶인 서버 생성 문구 — 같은 멱등키 재요청이 문구 스냅샷을 대조한다
    @Query("select a from Asset a where a.boundComposeJobId = :jobId "
            + "and a.purpose = com.harucut.asset.enums.AssetPurpose.TEXT_RENDER and a.origin = com.harucut.asset.enums.AssetOrigin.SERVER")
    java.util.Optional<Asset> findServerTextRenderByJob(@Param("jobId") Long jobId);

    // 이 key 들 중 자산 행이 있는 것 — 자산이 된 key 는 프레임 정리가 아니라 미참조 자산 정리 배치가 지운다
    @Query("select a.s3Key from Asset a where a.s3Key in :keys")
    List<String> findExistingKeys(Collection<String> keys);

    @Query("select a.s3Key from Asset a where a.workspaceId = :workspaceId")
    List<String> findKeysByWorkspaceId(Long workspaceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Asset a where a.workspaceId = :workspaceId")
    void deleteByWorkspaceId(Long workspaceId);

    // 결과가 확정된(DONE·FAILED) 작업에 묶였던 촬영 입력·서버 생성 문구 행 — S3 객체는 확정 때 이미 지웠다(ADR-0008 §7, ComposeJob.inputKeys).
    // 같은 합성 요청의 재생(멱등)이 보낸 입력 id 를 이 행으로 대조하므로, 재생 기간(cutoff 전에 만든 작업)이 지난 것만 지운다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Asset a where (a.purpose = com.harucut.asset.enums.AssetPurpose.CAPTURE_INPUT "
            + "or (a.purpose = com.harucut.asset.enums.AssetPurpose.TEXT_RENDER and a.origin = com.harucut.asset.enums.AssetOrigin.SERVER)) "
            + "and a.boundComposeJobId in (select j.id from ComposeJob j where j.status in "
            + "(com.harucut.media.enums.ComposeStatus.DONE, com.harucut.media.enums.ComposeStatus.FAILED) "
            + "and j.createdAt < :cutoff)")
    int deleteSettledCaptureInputs(@Param("cutoff") LocalDateTime cutoff);

    @Query("select coalesce(sum(1L * a.width * a.height), 0) from Asset a where a.s3Key in :keys and a.width is not null")
    long sumPixelsByKeys(@Param("keys") Collection<String> keys);

    long countByCaptureRunIdAndBoundComposeJobIdIsNull(Long captureRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.captureRunId = :captureRunId and a.boundComposeJobId is null")
    List<Asset> lockUnboundCaptureInputs(@Param("captureRunId") Long captureRunId);

    // 끝내 합성에 쓰이지 않은 촬영 입력 — 얼굴 사진이라 다른 자산보다 짧게 둔다. 회차·PUT 수명보다 길면 진행 중인 촬영을 건드리지 않는다
    @Query("""
            select a from Asset a
            where a.id > :afterId and a.createdAt < :cutoff
              and a.purpose = com.harucut.asset.enums.AssetPurpose.CAPTURE_INPUT and a.boundComposeJobId is null
            order by a.id
            """)
    List<Asset> findAbandonedCaptureInputs(@Param("cutoff") LocalDateTime cutoff, @Param("afterId") Long afterId, Pageable pageable);

    // ── 미참조 자산 정리(ADR-0006 §2) — 참조 판정은 이 저장소 한 곳에 모은다 ──

    /**
     * 정리 후보: 유예(cutoff) 전에 만들어졌고 id 로 참조하는 곳(프레임 컴포넌트·발행본·초안)이 없는 자산.
     * 미검증(PENDING_UPLOAD·REJECTED)은 용도와 무관하게, 검증된 것은 참조 규칙이 있는 용도만 —
     * 프레임 자산·제작자가 구운 글자, 그리고 어느 합성에도 묶이지 않은 촬영 입력. 서버가 구운 참가자 문구는 작업에 묶여
     * 작업 확정 때 지워지므로(ComposeJob.inputKeys) 여기서 고르지 않는다 — 진행 중인 작업의 레이어를 지우지 않게. 프로필·페이지 테마는 참조 규칙이 생길 때까지 두지 않는다.
     * key 문자열 참조(배경 JSON·예전 컴포넌트 key)는 FK 가 없어 호출자가 따로 거른다.
     */
    @Query("""
            select a from Asset a
            where a.id > :afterId and a.createdAt < :cutoff
              and (a.status <> com.harucut.asset.enums.AssetStatus.VERIFIED
                   or a.purpose = com.harucut.asset.enums.AssetPurpose.FRAME_ASSET
                   or (a.purpose = com.harucut.asset.enums.AssetPurpose.TEXT_RENDER and a.origin <> com.harucut.asset.enums.AssetOrigin.SERVER)
                   or (a.purpose = com.harucut.asset.enums.AssetPurpose.CAPTURE_INPUT and a.boundComposeJobId is null))
              and not exists (select 1 from FrameComponent c where c.assetId = a.id or c.renderedAssetId = a.id)
              and not exists (select 1 from FrameVersionAsset v where v.assetId = a.id)
              and not exists (select 1 from FrameDraftAsset d where d.assetId = a.id)
            order by a.id
            """)
    List<Asset> findOrphanCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("afterId") Long afterId, Pageable pageable);

    // key 문자열로 쓰는 곳 — 발행 때 편입된 예전 key 는 프레임에 assetId 없이 문자열로 남아 있다
    @Query("select c.source from FrameComponent c where c.source in :keys")
    List<String> findComponentSourcesIn(@Param("keys") Collection<String> keys);

    @Query("select c.renderedKey from FrameComponent c where c.renderedKey in :keys")
    List<String> findRenderedKeysIn(@Param("keys") Collection<String> keys);

    @Query("select f.previewKey from Frame f where f.previewKey in :keys")
    List<String> findPreviewKeysIn(@Param("keys") Collection<String> keys);

    // 배경은 JSON 문자열 컬럼(변환기)이라 JPQL 로 LIKE 를 걸 수 없다 — 네이티브. '_' 는 와일드카드지만 넓게 걸릴 뿐(지우지 않는 쪽)이다
    @Query(value = "select count(*) from frame where background like :pattern", nativeQuery = true)
    long countFramesWithBackgroundLike(@Param("pattern") String pattern);

    // 끝나지 않은 합성의 스냅샷(spec_json)이 가리키는 key — 합성 접수는 레이어·배경 key 를 스냅샷만 하고 자산 참조를 만들지 않는다.
    // 그사이 프레임에서 빠진 자산을 지우면 그 합성이 원본 없음으로 실패한다(예전 key 의 FrameAssetResolver.keysUsedByPendingJobs 와 같은 보호).
    // spec_json 은 변환기 칸이라 네이티브다. PENDING 은 적어 스캔이 짧다. '_' 는 넓게 걸릴 뿐(지우지 않는 쪽)이다
    @Query(value = "select count(*) from compose_job where status = 'PENDING' and spec_json like :pattern", nativeQuery = true)
    long countPendingComposeSpecsLike(@Param("pattern") String pattern);

    // 지우기 직전 다시 본다 — 후보를 고른 뒤 누가 id 로 참조하기 시작했거나 끝나지 않은 합성이 이 key 를 스냅샷에 담았으면 0 이 나와 건너뛴다.
    // spec_json 조건 때문에 네이티브다. pattern 은 호출자가 이 자산의 key 로 만든다 — 지우는 테이블을 하위 질의로 다시 읽으면 MySQL 이 막는다(1093)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            delete from asset where asset_id = :id and bound_compose_job_id is null
              and not exists (select 1 from frame_component c where c.asset_id = :id or c.rendered_asset_id = :id)
              and not exists (select 1 from frame_version_asset v where v.asset_id = :id)
              and not exists (select 1 from frame_draft_asset d where d.asset_id = :id)
              and not exists (select 1 from compose_job j where j.status = 'PENDING' and j.spec_json like :pattern)
            """, nativeQuery = true)
    int deleteIfUnreferenced(@Param("id") Long id, @Param("pattern") String pattern);

    // ── 탈퇴(AssetUserDeletionHandler) — 개인 공간 밖에서 쓰는 개인 자산은 지우지 않고 그 공간으로 옮긴다 ──

    List<Asset> findAllByWorkspaceIdOrderByIdAsc(Long workspaceId);

    /**
     * 이 개인 자산을 개인 공간 밖에서 쓰는 프레임·초안의 공간 id. 시스템 프레임이면 NULL 이다.
     * 참조 기준은 미참조 정리와 같다 — id 참조(컴포넌트·발행본·초안)와 key 문자열 참조(컴포넌트 source·renderedKey·프리뷰·배경 JSON).
     * 개인 공간 프레임과 떠나는 회원 본인의 초안은 세지 않는다 — 다른 탈퇴 핸들러가 지운다(FrameUserDeletionHandler)
     */
    @Query(value = """
            select f.workspace_id from frame f
            where (f.workspace_id is null or f.workspace_id <> :personalWorkspaceId)
              and (exists (select 1 from frame_component c where c.frame_id = f.frame_id
                           and (c.asset_id = :assetId or c.rendered_asset_id = :assetId or c.source = :key or c.rendered_key = :key))
                   or exists (select 1 from frame_version v join frame_version_asset va on va.frame_version_id = v.frame_version_id
                              where v.frame_id = f.frame_id and va.asset_id = :assetId)
                   or f.preview_key = :key
                   or f.background like :pattern)
            union
            select d.workspace_id from frame_draft d join frame_draft_asset da on da.frame_draft_id = d.frame_draft_id
            where da.asset_id = :assetId and d.workspace_id <> :personalWorkspaceId and d.user_id <> :userId
            """, nativeQuery = true)
    List<Long> findWorkspacesUsingOutside(@Param("assetId") Long assetId, @Param("key") String key,
                                          @Param("pattern") String pattern, @Param("personalWorkspaceId") Long personalWorkspaceId,
                                          @Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Asset a set a.workspaceId = :workspaceId where a.id = :id")
    int moveToWorkspace(@Param("id") Long id, @Param("workspaceId") Long workspaceId);
}
