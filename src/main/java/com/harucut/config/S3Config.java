package com.harucut.config;

import com.harucut.storage.config.AwsProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

@Configuration
public class S3Config {

    // 호출 하나의 전체 제한 시간 — 묶음 작업자의 진행 신호는 전송과 따로 돌아서, 멈춘 호출이 끝나지 않으면 그 묶음이 영영 RUNNING 으로
    // 남는다. 요청·응답은 이 시간으로, 내려받는 본문 읽기는 HTTP 클라이언트의 소켓 읽기 제한(기본 30초)으로 끊긴다.
    // 8MB 파트 업로드가 2분 안에 끝나지 않는 망이면 실패가 맞다
    @Bean
    public S3Client s3Client(AwsProperties properties, @Value("${storage.s3.api-call-timeout:2m}") Duration apiCallTimeout) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(apiCallTimeout))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .build();
    }
}
