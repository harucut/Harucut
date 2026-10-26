package com.harucut.asset.batch;

import com.harucut.asset.entity.Asset;
import com.harucut.asset.repository.AssetRepository;
import com.harucut.storage.config.StorageProperties;
import com.harucut.storage.service.S3Deleter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 어디서도 참조하지 않는 자산을 지운다 (ADR-0006 §2). 참조는 DB 관계로만 판정한다 —
 * id 참조(프레임 컴포넌트·발행본·초안)와 key 문자열 참조(프레임 배경 JSON·프리뷰·예전 컴포넌트 key).
 *
 * <p>자산 하나를 한 트랜잭션으로 지우고, S3 객체는 그 행 삭제가 커밋된 뒤에 지운다. 그사이 누가 참조하기 시작하면
 * 조건부 삭제가 0 을 돌려주거나 FK 가 막아 그 자산만 건너뛴다. 같은 자산을 두 서버가 함께 지워도 결과가 같다(멱등).
 */
@Slf4j
@Component
public class AssetOrphanCleaner {

    private final AssetRepository assetRepository;
    private final S3Deleter s3Deleter;
    private final StorageProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final int maxPerRun;

    public AssetOrphanCleaner(AssetRepository assetRepository, S3Deleter s3Deleter, StorageProperties properties,
                              PlatformTransactionManager transactionManager, Clock clock,
                              @Value("${storage.asset.orphan-cleanup.batch-size:200}") int batchSize,
                              @Value("${storage.asset.orphan-cleanup.max-per-run:5000}") int maxPerRun) {
        this.assetRepository = assetRepository;
        this.s3Deleter = s3Deleter;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxPerRun = maxPerRun;
    }

    /** @return 지운 자산 수 */
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.asset().orphanAfter());
        long afterId = 0;
        int deleted = 0;
        while (deleted < maxPerRun) {
            List<Asset> candidates = assetRepository.findOrphanCandidates(cutoff, afterId, PageRequest.of(0, batchSize));
            if (candidates.isEmpty()) {
                break;
            }
            afterId = candidates.getLast().getId();
            Set<String> referencedByKey = referencedByKey(candidates);
            for (Asset asset : candidates) {
                if (deleted >= maxPerRun) {
                    break;
                }
                if (!referencedByKey.contains(asset.getS3Key()) && deleteOne(asset)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private Set<String> referencedByKey(List<Asset> candidates) {
        List<String> keys = candidates.stream().map(Asset::getS3Key).toList();
        Set<String> referenced = new HashSet<>();
        referenced.addAll(assetRepository.findComponentSourcesIn(keys));
        referenced.addAll(assetRepository.findRenderedKeysIn(keys));
        referenced.addAll(assetRepository.findPreviewKeysIn(keys));
        for (String key : keys) {
            if (!referenced.contains(key) && assetRepository.countFramesWithBackgroundLike("%" + key + "%") > 0) {
                referenced.add(key);
            }
        }
        return referenced;
    }

    private boolean deleteOne(Asset asset) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                if (assetRepository.deleteIfUnreferenced(asset.getId()) != 1) {
                    return false;
                }
                s3Deleter.deleteAfterCommit(List.of(asset.getS3Key()));
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            // 조건 검사와 삭제 사이에 FK 참조가 생겼다 — 이번엔 두고 다음 실행이 다시 본다
            log.info("[자산 정리] {} 참조가 생겨 건너뜀", asset.getPublicId());
            return false;
        }
    }
}
