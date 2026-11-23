package com.harucut.storage.event;

import com.harucut.frame.service.FrameAssetManager;
import com.harucut.storage.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

// 이 장치의 존재 이유 그 자체를 고정한다: 삭제는 커밋 후에만 일어나고, 롤백이면 아무 일도 없다.
// 유닛 테스트는 "발행한다"와 "받으면 지운다"를 따로 증명한다 — 이 테스트가 그 사이의 트랜잭션 경계를 잇는다
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("S3 삭제 커밋/롤백 의미론")
class S3DeleteFlowTest {

    private static final String KEY = "uploads/users/AbCdEf12Gh/webm/old-preview.png";

    @Autowired
    private FrameAssetManager frameAssetManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private FileStorageService fileStorageService;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("트랜잭션 안에서는 아무것도 지워지지 않고, 커밋 후에만 지워진다")
    void deletesOnlyAfterCommit() {
        transactionTemplate.executeWithoutResult(status -> {
            frameAssetManager.deleteAfterCommit(List.of(KEY));
            // 아직 트랜잭션 안 — 삭제가 실행됐다면 롤백 시 파일만 사라지는 Kotlin의 문제가 재현된 것
            then(fileStorageService).should(never()).delete(KEY);
        });

        then(fileStorageService).should(timeout(5000)).delete(KEY);   // 커밋 뒤 전용 스레드에서
    }

    @Test
    @DisplayName("롤백되면 삭제는 영원히 실행되지 않는다 — 아무 일도 없던 것이 된다")
    void neverDeletesOnRollback() {
        transactionTemplate.executeWithoutResult(status -> {
            frameAssetManager.deleteAfterCommit(List.of(KEY));
            status.setRollbackOnly();
        });

        then(fileStorageService).should(never()).delete(KEY);
    }
}
