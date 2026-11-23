package com.harucut.admin;

import com.harucut.admin.dto.AdminSearchDtos;
import com.harucut.admin.service.AdminSearchService;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.enums.EventStatus;
import com.harucut.event.service.EventService;
import com.harucut.storage.service.FileStorageService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("관리자 공간·행사 찾기 — 통합")
class AdminSearchIntegrationTest {

    @Autowired private AdminSearchService searchService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private EventService eventService;
    @Autowired private UserRepository userRepository;

    @MockitoBean private FileStorageService fileStorageService;

    private User owner;
    private WorkspaceResponse org;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.localUser("hall-owner@wedding.test", "e", "오너"));
        org = workspaceService.createOrganization(owner.getPublicId(), "그랜드 웨딩홀");
    }

    @Test
    @DisplayName("공간: 이름·OWNER 이메일 부분 일치(대소문자 무시), 개인 공간은 나오지 않는다")
    void workspaces() {
        assertThat(searchService.workspaces("그랜드")).singleElement().satisfies(w -> {
            assertThat(w.workspaceId()).isEqualTo(org.workspaceId());
            assertThat(w.ownerEmails()).containsExactly("hall-owner@wedding.test");
        });
        assertThat(searchService.workspaces("HALL-OWNER")).extracting(AdminSearchDtos.WorkspaceItem::workspaceId)
                .containsExactly(org.workspaceId());
        workspaceService.listMine(owner.getPublicId());   // 개인 공간을 만든다
        assertThat(searchService.workspaces("wedding.test")).hasSize(1);
    }

    @Test
    @DisplayName("행사: slug·이름 부분 일치, 공간 id·이름·상태가 함께 나온다")
    void events() {
        EventResponse event = eventService.create(owner.getPublicId(), org.workspaceId(),
                new EventSettings("민지 ♥ 준호", "minji-junho", null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(searchService.events("junho")).singleElement().satisfies(e -> {
            assertThat(e.eventId()).isEqualTo(event.eventId());
            assertThat(e.slug()).isEqualTo("minji-junho");
            assertThat(e.status()).isEqualTo(EventStatus.DRAFT);
            assertThat(e.workspaceId()).isEqualTo(org.workspaceId());
            assertThat(e.workspaceName()).isEqualTo("그랜드 웨딩홀");
        });
        assertThat(searchService.events("민지")).hasSize(1);
    }

    @Test
    @DisplayName("최대 20건, 「%」「_」는 글자로 찾는다, 두 글자 미만은 GEN-002")
    void limitsAndEscaping() {
        for (int i = 0; i < 21; i++) {
            workspaceService.createOrganization(owner.getPublicId(), "지점 " + i);
        }
        assertThat(searchService.workspaces("지점")).hasSize(20);
        assertThat(searchService.workspaces("%%")).isEmpty();
        assertThat(searchService.workspaces("__")).isEmpty();
        assertThatThrownBy(() -> searchService.events("a"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(GlobalErrorCode.INVALID_INPUT_VALUE);
    }
}
