package com.harucut.frame.adapter;

import com.harucut.frame.repository.FrameRepository;
import com.harucut.subscription.port.FrameCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FrameCountAdapter implements FrameCountPort {

    private final FrameRepository frameRepository;

    @Override
    public int countByUserId(Long userId) {
        return (int) frameRepository.countPersonalByUserId(userId);
    }
}
