package com.harucut.media.compose;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 새 작업에 매길 출력 계약(R5 D09). 기본은 예전 계약(1) — Lambda 가 계약 2 를 아는 버전으로 배포된 것을 확인한 뒤
 * compose.output-contract=2 로 올린다. 올리기 전에도 소비자는 두 계약의 통지를 모두 처리한다(배포 순서: 소비자 → Lambda → 이 값).
 * ⚠️ Lambda 배포·테스트 버킷의 ChecksumMode 권한 확인은 실연동 미확인
 */
@Component
public class ComposeOutputPolicy {

    private final int contractForNewJobs;

    public ComposeOutputPolicy(@Value("${compose.output-contract:1}") int contractForNewJobs) {
        if (contractForNewJobs != ComposeOutputContract.LEGACY && contractForNewJobs != ComposeOutputContract.VERIFIED_PAIR) {
            throw new IllegalStateException("compose.output-contract 는 1 또는 2 다: " + contractForNewJobs);
        }
        this.contractForNewJobs = contractForNewJobs;
    }

    public int contractForNewJobs() {
        return contractForNewJobs;
    }
}
