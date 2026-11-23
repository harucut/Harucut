package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;

/**
 * 프레임 PUT 의 낙관적 잠금 (ADR-0006 §3). 사용자·관리자 수정이 같은 규칙을 쓴다.
 *
 * <p>두 겹이다. 요청이 version 을 보내면 먼저 현재 값과 비교하고, 보내지 않았더라도 두 저장이 같은 version 을
 * 읽고 동시에 쓰면 flush 에서 JPA(@Version)가 막는다. 어느 쪽이든 409 FRAME-002 + 현재 version.
 */
final class FrameVersions {

    private FrameVersions() {
    }

    static void assertExpected(Frame frame, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(frame.getVersion())) {
            throw conflict(frame.getVersion());
        }
    }

    static void flush(FrameRepository frameRepository, Frame frame) {
        try {
            frameRepository.saveAndFlush(frame);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 지금 읽은 값은 낡았다 — 정확한 현재 값은 다시 불러와야 알 수 있다
            throw new BusinessException(FrameErrorCode.VERSION_CONFLICT, "동시 저장 충돌: frameId=" + frame.getId());
        }
    }

    private static BusinessException conflict(Long currentVersion) {
        return new BusinessException(FrameErrorCode.VERSION_CONFLICT,
                "프레임 version 불일치: 현재 " + currentVersion,
                Map.of("currentVersion", currentVersion));
    }
}
