package com.harucut.support.mysql;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 실제 MySQL 에 Flyway migration 을 처음부터 적용하고, Hibernate validate 로 엔티티와 대조한다.
 * 컨텍스트가 뜨면 통과다 — migration 문법 오류·엔티티와 다른 컬럼 타입이면 기동에서 죽는다.
 *
 * 기본 test 에서는 빠진다(Docker 필요). ./gradlew mysqlTest
 * 새 migration 을 추가하는 PR 은 이 테스트를 돌린 결과를 적는다.
 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@DisplayName("Flyway migration — 실제 MySQL")
class FlywayMysqlMigrationTest {

    // 운영과 같은 메이저 버전·문자셋으로 띄운다 (docker-compose.yml)
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("빈 DB 에 모든 migration 이 적용되고 엔티티 validate 를 통과한다")
    void migratesAndValidates() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'compose_job'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("약관 동의는 회원 FK 를 가진다(V27)")
    void termsAgreementUserForeignKey() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT referenced_table_name FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_terms_agreement_user'
                """, String.class)).isEqualTo("users");
    }
}
