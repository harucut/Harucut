package com.harucut.support.mysql;

import com.harucut.admin.service.AdminSearchService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/* LIKE … ESCAPE '!' 가 MySQL 에서도 같은 뜻인지(백슬래시 이스케이프를 피한 이유) 실제로 돌린다 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.schema-locations=classpath:org/springframework/batch/core/schema-mysql.sql"
})
@ActiveProfiles("test")
@Transactional
@DisplayName("관리자 검색 — 실제 MySQL")
class AdminSearchMysqlTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired private AdminSearchService searchService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserRepository userRepository;

    @MockitoBean private FileStorageService fileStorageService;

    @Test
    @DisplayName("부분 일치와 와일드카드 글자 검색이 MySQL 에서도 같다")
    void searchesOnMysql() {
        User owner = userRepository.save(User.localUser("owner@search.test", "e", "오너"));
        workspaceService.createOrganization(owner.getPublicId(), "100% 웨딩");
        workspaceService.createOrganization(owner.getPublicId(), "웨딩_홀");

        assertThat(searchService.workspaces("100%")).hasSize(1);
        assertThat(searchService.workspaces("g_")).isEmpty();
        assertThat(searchService.workspaces("search.test")).hasSize(2);
        assertThat(searchService.events("없는행사")).isEmpty();
    }
}
