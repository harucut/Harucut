package com.harucut.support.mysql;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * V19(프로젝트·상속)는 기존 행사의 컬럼 값을 초안(직접 값) JSON 으로 옮긴다 — 빈 DB 적용만으로는 그 이전이 검증되지 않으므로,
 * V18 까지 올린 DB 에 행사를 넣고 V19 를 적용해 본다. 기존 행사는 모든 값이 직접 값이 되어 상위 기본값이 생겨도 바뀌지 않아야 한다.
 */
@Tag("mysql")
@Testcontainers
@DisplayName("V19 프로젝트·상속 — 실제 MySQL, 기존 행사")
class ProjectMigrationMysqlTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Test
    @DisplayName("기존 행사의 시간대·참가 채널·보관 일수가 초안 JSON 에 직접 값으로 들어가고, 원래 초안 키는 남는다")
    void copiesColumnsIntoDraft() throws SQLException {
        flyway("18").migrate();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO users (created_at, updated_at, email, profile_image_url, provider, public_id, user_role, user_status, username)
                    VALUES (NOW(6), NOW(6), 'a@harucut.com', '', 'HARUCUT', 'AbCdEf12Gh34', 'ROLE_USER', 'ACTIVE', 'a')""");
            st.executeUpdate("""
                    INSERT INTO workspace (created_at, updated_at, public_id, type, name)
                    VALUES (NOW(6), NOW(6), 'Ws0000000001', 'ORGANIZATION', '웨딩홀')""");
            st.executeUpdate("""
                    INSERT INTO event (created_at, updated_at, public_id, workspace_id, slug, name, status, paused, timezone,
                        byod_enabled, booth_enabled, result_retention_days, settings_draft, settings_version, created_by_user_id)
                    VALUES (NOW(6), NOW(6), 'Ev0000000001', (SELECT workspace_id FROM workspace), 'legacy', '옛 행사', 'LIVE', 0,
                        'Asia/Tokyo', 0, 1, 7, '{"allowedLayouts":["CLASSIC"],"frames":[],"consentItems":[]}', 3,
                        (SELECT user_id FROM users))""");
        }

        flyway(null).migrate();

        try (Connection c = connection(); Statement st = c.createStatement()) {
            ResultSet row = st.executeQuery("SELECT settings_draft, project_id FROM event");
            row.next();
            JsonNode draft = JsonMapper.builder().build().readTree(row.getString("settings_draft"));
            assertThat(draft.path("timezone").asString()).isEqualTo("Asia/Tokyo");
            assertThat(draft.path("byodEnabled").isBoolean()).isTrue();
            assertThat(draft.path("byodEnabled").asBoolean()).isFalse();
            assertThat(draft.path("boothEnabled").asBoolean()).isTrue();
            assertThat(draft.path("resultRetentionDays").asInt()).isEqualTo(7);
            assertThat(draft.path("allowedLayouts").get(0).asString()).isEqualTo("CLASSIC");
            assertThat(row.getObject("project_id")).isNull();

            assertThat(st.executeQuery("SHOW TABLES LIKE 'project'").next()).isTrue();
            assertThat(st.executeQuery("SHOW COLUMNS FROM workspace LIKE 'brand_defaults'").next()).isTrue();
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
