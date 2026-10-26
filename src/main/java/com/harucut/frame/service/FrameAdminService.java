package com.harucut.frame.service;

import com.harucut.common.exception.BusinessException;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.exception.FrameErrorCode;
import com.harucut.frame.repository.FrameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 시스템(기본 제공) 프레임 관리자 CRUD. 조립·교체·수집 규칙은 어셈블러가 소유하고
// 이 서비스는 관문(시스템 프레임 확인)만 다르다. 요금제 한도·보관 기간 검사가 없는 것은
// 의존성 자체가 없는 것으로 구조적으로 보장된다 — FrameSubscriptionPolicy를 모른다
@Service
@Transactional
@RequiredArgsConstructor
public class FrameAdminService {

    private final FrameRepository frameRepository;
    private final FrameVersionCleaner frameVersionCleaner;
    private final FrameComponentAssembler frameComponentAssembler;

    public FrameResponse createSystemFrame(FrameCreateRequest request) {
        Frame frame = frameComponentAssembler.assembleSystem(request);
        return frameComponentAssembler.toFrameResponse(frameRepository.save(frame));
    }

    public FrameResponse updateSystemFrame(Long frameId, FrameCreateRequest request) {
        Frame frame = findSystemFrame(frameId);
        FrameVersions.assertExpected(frame, request.version());
        frameComponentAssembler.replaceContent(frame, request);
        FrameVersions.flush(frameRepository, frame);
        return frameComponentAssembler.toFrameResponse(frame);
    }

    public void deleteSystemFrame(Long frameId) {
        Frame frame = findSystemFrame(frameId);
        frameComponentAssembler.scheduleDeletion(frameComponentAssembler.collectAllKeys(frame));
        frameVersionCleaner.deleteForFrames(List.of(frame.getId()));
        frameRepository.delete(frame);
    }

    @Transactional(readOnly = true)
    public List<FrameResponse> listSystemFrames() {
        return frameRepository.findAllWithComponentsBySystem().stream()
                .map(frameComponentAssembler::toFrameResponse)
                .toList();
    }

    // 없거나 시스템 프레임이 아니면(=사용자 프레임 id 조작 시도) 같은 404 — 존재를 확인시켜주지 않는다
    private Frame findSystemFrame(Long frameId) {
        Frame frame = frameRepository.findById(frameId)
                .orElseThrow(() -> new BusinessException(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND));
        if (!frame.isSystem()) {
            throw new BusinessException(FrameErrorCode.SYSTEM_FRAME_NOT_FOUND);
        }
        return frame;
    }
}
