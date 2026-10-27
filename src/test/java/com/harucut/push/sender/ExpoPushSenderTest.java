package com.harucut.push.sender;

import com.harucut.push.service.PushMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Expo 푸시 발송기")
class ExpoPushSenderTest {

    private static final String URL = "https://exp.host/--/api/v2/push/send";

    @Test
    @DisplayName("DeviceNotRegistered 인 토큰만 지울 대상으로 돌려준다 — 다른 오류는 남긴다")
    void returnsUnregistered() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST))
                .andExpect(jsonPath("$[0].to").value("ExpoPushToken[a]"))
                .andExpect(jsonPath("$[0].title").value("사진이 완성됐어요"))
                .andExpect(jsonPath("$[0].data.type").value("COMPOSE_DONE"))
                .andExpect(jsonPath("$[0].data.jobId").value("7"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"status":"ok","id":"t-1"},
                          {"status":"error","message":"gone","details":{"error":"DeviceNotRegistered"}},
                          {"status":"error","message":"rate","details":{"error":"MessageRateExceeded"}}
                        ]}""", MediaType.APPLICATION_JSON));

        ExpoPushSender sender = new ExpoPushSender(builder.build());

        assertThat(sender.send(List.of("ExpoPushToken[a]", "ExpoPushToken[b]", "ExpoPushToken[c]"),
                PushMessage.composeDone(7L))).containsExactly("ExpoPushToken[b]");
        server.verify();
    }

    @Test
    @DisplayName("100건씩 나눠 보내고, 한 묶음이 실패해도 나머지는 보낸다(예외를 내지 않는다)")
    void batchesAndSwallowsFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.POST)).andRespond(withServerError());
        server.expect(method(HttpMethod.POST)).andExpect(jsonPath("$.length()").value(5))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<String> tokens = IntStream.range(0, ExpoPushSender.BATCH + 5).mapToObj(i -> "ExpoPushToken[" + i + "]").toList();

        assertThat(new ExpoPushSender(builder.build()).send(tokens, PushMessage.composeFailed(1L, "UNKNOWN")))
                .containsExactly("ExpoPushToken[100]");
        server.verify();
    }
}
