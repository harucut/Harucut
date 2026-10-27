package com.harucut.asset.repository;

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

    // 지우기 직전 다시 본다 — 후보를 고른 뒤 누가 id 로 참조하기 시작했으면 0 이 나와 건너뛴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from Asset a where a.id = :id
              and not exists (select 1 from FrameComponent c where c.assetId = :id or c.renderedAssetId = :id)
              and not exists (select 1 from FrameVersionAsset v where v.assetId = :id)
              and not exists (select 1 from FrameDraftAsset d where d.assetId = :id)
            """)
    int deleteIfUnreferenced(@Param("id") Long id);
}
