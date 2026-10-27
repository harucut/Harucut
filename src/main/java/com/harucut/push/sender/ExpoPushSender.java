package com.harucut.push.sender;

import com.harucut.push.entity.DeviceToken;
import com.harucut.push.service.PushMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expo Push API 발송기(push.sender=expo). ⚠️ 실연동 미확인 — EAS projectId·액세스 토큰이 필요하다.
 *
 * <p>요청 하나에 100건까지. 응답 data[] 는 보낸 순서와 같고, details.error=DeviceNotRegistered 인 토큰은 지울 대상으로 돌려준다.
 * 영수증(receipt) 조회는 하지 않는다 — 즉시 응답의 오류만 본다(최대 한 번 발송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "push.sender", havingValue = "expo")
public class ExpoPushSender implements PushSender {

    static final int BATCH = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public ExpoPushSender(RestClient.Builder builder,
                          @Value("${push.expo.url:https://exp.host/--/api/v2/push/send}") String url,
                          @Value("${push.expo.access-token:}") String accessToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        RestClient.Builder configured = builder.clone().requestFactory(factory).baseUrl(url);
        if (StringUtils.hasText(accessToken)) {
            configured.defaultHeader("Authorization", "Bearer " + accessToken);
        }
        this.restClient = configured.build();
    }

    // 테스트가 MockRestServiceServer 를 묶은 빌더를 그대로 쓴다
    ExpoPushSender(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        Set<String> unregistered = new HashSet<>();
        for (int from = 0; from < tokens.size(); from += BATCH) {
            List<String> batch = tokens.subList(from, Math.min(tokens.size(), from + BATCH));
            try {
                unregistered.addAll(sendBatch(batch, message));
            } catch (RuntimeException e) {
                log.warn("[푸시] Expo 발송 실패 type={} devices={}", message.type(), batch.size(), e);
            }
        }
        return unregistered;
    }

    private Set<String> sendBatch(List<String> batch, PushMessage message) {
        List<Map<String, Object>> body = batch.stream().map(token -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("to", token);
            item.put("title", message.type().title());
            item.put("body", message.type().body());
            item.put("data", message.data());
            item.put("sound", "default");
            return item;
        }).toList();

        JsonNode response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        Set<String> unregistered = new HashSet<>();
        JsonNode tickets = response == null ? null : response.path("data");
        if (tickets == null || !tickets.isArray()) {
            log.warn("[푸시] Expo 응답에 data 배열이 없다 type={}", message.type());
            return unregistered;
        }
        for (int i = 0; i < tickets.size() && i < batch.size(); i++) {
            JsonNode ticket = tickets.get(i);
            if ("error".equals(ticket.path("status").asString(""))) {
                String error = ticket.path("details").path("error").asString("");
                log.info("[푸시] Expo 오류 token={} error={}", DeviceToken.mask(batch.get(i)), error);
                if ("DeviceNotRegistered".equals(error)) {
                    unregistered.add(batch.get(i));
                }
            }
        }
        return unregistered;
    }
}
