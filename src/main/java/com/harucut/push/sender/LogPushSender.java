package com.harucut.push.sender;

import com.harucut.push.entity.DeviceToken;
import com.harucut.push.service.PushMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

// 기본 발송기 — 로그만 남긴다(로컬·테스트, 그리고 Expo 실연동 확인 전 운영)
@Slf4j
@Component
@ConditionalOnProperty(name = "push.sender", havingValue = "log", matchIfMissing = true)
public class LogPushSender implements PushSender {

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        log.info("[푸시] (로그 발송기) type={} data={} devices={}", message.type(), message.data(),
                tokens.stream().map(DeviceToken::mask).toList());
        return Set.of();
    }
}
