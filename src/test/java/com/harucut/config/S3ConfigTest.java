package com.harucut.config;

import com.harucut.storage.config.AwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("S3 클라이언트 구성")
class S3ConfigTest {

    @Test
    @DisplayName("호출 하나의 전체 제한 시간을 둔다 — 멈춘 호출이 묶음을 영영 RUNNING 으로 붙잡지 않게(진행 신호는 전송과 따로 돈다)")
    void apiCallTimeout() {
        AwsProperties properties = mock(AwsProperties.class);
        given(properties.region()).willReturn("ap-northeast-2");

        try (S3Client client = new S3Config().s3Client(properties, Duration.ofMinutes(2))) {
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(Duration.ofMinutes(2));
        }
    }
}
