package com.harucut.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 무중단 배포용 프로브 (ADR-0009 §7-3) — readiness 는 DB 까지, liveness 는 앱 자체만, Redis 는 어느 프로브에도 없다.
 * 배포 스크립트가 보는 전체 health 는 지금처럼 Redis 를 포함한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("헬스 프로브 구성")
class HealthProbesTest {

    @Autowired private HealthEndpointGroups groups;
    @Autowired private HealthEndpoint healthEndpoint;

    @Test
    @DisplayName("readiness = readinessState + db, liveness = livenessState — Redis 는 프로브에서 빠지고 전체 health 에만")
    void probes() {
        HealthEndpointGroup readiness = groups.get("readiness");
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("redis")).isFalse();

        HealthEndpointGroup liveness = groups.get("liveness");
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember("db")).isFalse();
        assertThat(liveness.isMember("redis")).isFalse();

        assertThat(groups.getPrimary().isMember("redis")).isTrue();
        assertThat(healthEndpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("liveness").getStatus()).isEqualTo(Status.UP);
    }
}
