package com.harucut.notice.controller;

import com.harucut.terms.service.TermsConsentGuard;
import com.harucut.notice.service.NoticeAdminService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;

@WebMvcTest(NoticeAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class NoticeAdminControllerTest {

    // WebMvcConfig 의 약관 동의 인터셉터가 요구한다 — 기본값 false 라 동의 강제는 걸리지 않는다
    @MockitoBean
    private TermsConsentGuard termsConsentGuard;

    @Autowired
    private MockMvcTester mockMvc;

    @MockitoBean
    private NoticeAdminService noticeAdminService;

    @Test
    @DisplayName("title이 없으면 GEN-003과 함께 어느 필드가 문제인지 알려준다")
    void missingTitle() {
        assertThat(post("""
                {"content":"본문","pinned":false}
                """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                .hasPathSatisfying("$.data[0].field", f -> assertThat(f).isEqualTo("title"))
                .hasPathSatisfying("$.data[0].message", m -> assertThat(m).isEqualTo("제목은 필수입니다."));

        then(noticeAdminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("title이 201자면 GEN-003을 반환한다")
    void titleTooLong() {
        assertThat(post(bodyWithTitle("a".repeat(201))))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-003"))
                .hasPathSatisfying("$.data[0].message", m -> assertThat(m).isEqualTo("제목은 200자 이하여야 합니다."));

        then(noticeAdminService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("title이 정확히 200자면 통과한다")
    void titleAtLimit() {
        assertThat(post(bodyWithTitle("a".repeat(200)))).hasStatusOk();

        then(noticeAdminService).should().createNotice(any());
    }

    private MvcTestResult post(String body) {
        return mockMvc.post().uri("/api/admin/notices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String bodyWithTitle(String title) {
        return """
                {"title":"%s","content":"본문","pinned":false}
                """.formatted(title);
    }
}