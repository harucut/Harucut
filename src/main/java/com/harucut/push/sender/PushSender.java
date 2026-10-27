package com.harucut.push.sender;

import com.harucut.push.service.PushMessage;

import java.util.List;
import java.util.Set;

public interface PushSender {

    /**
     * 기기들에 보낸다. 예외를 밖으로 내지 않는다.
     *
     * @return 더 이상 받지 않는 기기의 토큰(Expo DeviceNotRegistered) — 호출자가 지운다
     */
    Set<String> send(List<String> tokens, PushMessage message);
}
