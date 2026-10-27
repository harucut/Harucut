package com.harucut.push.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** data 는 전부 문자열이다 — type 이 항상 들어가고, 종류에 따라 jobId·failureCode 가 붙는다. */
public record PushMessage(PushType type, Map<String, String> data) {

    public PushMessage {
        Map<String, String> copy = new LinkedHashMap<>();
        copy.put("type", type.name());
        if (data != null) {
            data.forEach((key, value) -> {
                if (value != null) {
                    copy.put(key, value);
                }
            });
        }
        data = Map.copyOf(copy);
    }

    public static PushMessage composeDone(Long jobId) {
        return new PushMessage(PushType.COMPOSE_DONE, Map.of("jobId", String.valueOf(jobId)));
    }

    public static PushMessage composeFailed(Long jobId, String failureCode) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("jobId", String.valueOf(jobId));
        data.put("failureCode", failureCode);
        return new PushMessage(PushType.COMPOSE_FAILED, data);
    }

    public static PushMessage of(PushType type) {
        return new PushMessage(type, Map.of());
    }
}
