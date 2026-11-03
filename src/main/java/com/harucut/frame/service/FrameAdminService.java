package com.harucut.frame.service;

import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.repository.FrameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 시스템(기본 제공) 프레임 관리자 — 만들기·목록. 조립 규칙은 어셈블러가 소유한다. 요금제 한도·보관 기간 검사가 없는 것은
// 의존성 자체가 없는 것으로 구조적으로 보장된다 — FrameSubscriptionPolicy를 모른다.
// 수정·삭제는 없다(2026-11-03 정리) — 잘못 만든 프레임은 발행본 회수로 막고 새로 만든다
@Service
@Transactional
@RequiredArgsConstructor
public class FrameAdminService {

    private final FrameRepository frameRepository;
    private final FrameComponentAssembler frameComponentAssembler;

    public FrameResponse createSystemFrame(FrameCreateRequest request) {
        Frame frame = frameComponentAssembler.assembleSystem(request);
        return frameComponentAssembler.toFrameResponse(frameRepository.save(frame));
    }

    @Transactional(readOnly = true)
    public List<FrameResponse> listSystemFrames() {
        return frameRepository.findAllWithComponentsBySystem().stream()
                .map(frameComponentAssembler::toFrameResponse)
                .toList();
    }
}
