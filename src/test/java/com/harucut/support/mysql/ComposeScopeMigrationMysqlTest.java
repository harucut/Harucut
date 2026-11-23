package com.harucut.support.mysql;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * V7(멱등 scope 전환)은 기존 행을 백필하고 제약을 바꾸는 migration 이다. 빈 DB 적용(FlywayMysqlMigrationTest)만으로는
 * 백필이 검증되지 않으므로, V6 까지 올린 DB 에 개인 작업을 넣고 V7 을 적용해 본다.
 */
@Tag("mysql")
@Testcontainers
@DisplayName("V7 compose_job scope 전환 — 실제 MySQL, 기존 데이터")
class ComposeScopeMigrationMysqlTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Test
    @DisplayName("기존 작업이 U:{userId}·사용자 폴더로 백필되고, user_id 는 NULL 을 받으며 옛 유니크는 사라진다")
    void backfillsExistingJobs() throws SQLException {
        flyway("6").migrate();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO users (created_at, updated_at, email, profile_image_url, provider, public_id, user_role, user_status, username)
                    VALUES (NOW(6), NOW(6), 'a@harucut.com', '', 'HARUCUT', 'AbCdEf12Gh34', 'ROLE_USER', 'ACTIVE', 'a')""");
            st.executeUpdate("""
                    INSERT INTO compose_job (created_at, updated_at, frame_id, idempotency_key, source_key_1, source_key_2,
                        source_key_3, source_key_4, spec_json, status, user_id)
                    VALUES (NOW(6), NOW(6), 7, 'k1', 'a', 'b', 'c', 'd', '{}', 'DONE',
                        (SELECT user_id FROM users WHERE public_id = 'AbCdEf12Gh34'))""");
        }

        flyway(null).migrate();

        try (Connection c = connection(); Statement st = c.createStatement()) {
            ResultSet row = st.executeQuery("SELECT idempotency_scope, storage_root, user_id FROM compose_job");
            row.next();
            assertThat(row.getString("idempotency_scope")).isEqualTo("U:" + row.getLong("user_id"));
            assertThat(row.getString("storage_root")).isEqualTo("uploads/users/AbCdEf12Gh34/");

            ResultSet indexes = st.executeQuery("SHOW INDEX FROM compose_job WHERE Key_name = 'uk_compose_job_user_idempotency'");
            assertThat(indexes.next()).as("옛 유니크가 남아 있다").isFalse();

            // 행사 참가자 작업처럼 user 없이 들어갈 수 있다
            st.executeUpdate("""
                    INSERT INTO compose_job (created_at, updated_at, frame_id, idempotency_key, idempotency_scope, storage_root,
                        source_key_1, source_key_2, source_key_3, source_key_4, spec_json, status, user_id)
                    VALUES (NOW(6), NOW(6), 7, 'k1', 'P:1', 'uploads/events/e/', 'a', 'b', 'c', 'd', '{}', 'PENDING', NULL)""");
            // 같은 scope·key 는 막힌다
            assertThatThrownBy(() -> st.executeUpdate("""
                    INSERT INTO compose_job (created_at, updated_at, frame_id, idempotency_key, idempotency_scope, storage_root,
                        source_key_1, source_key_2, source_key_3, source_key_4, spec_json, status, user_id)
                    VALUES (NOW(6), NOW(6), 7, 'k1', 'P:1', 'uploads/events/e/', 'a', 'b', 'c', 'd', '{}', 'PENDING', NULL)"""))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
}
