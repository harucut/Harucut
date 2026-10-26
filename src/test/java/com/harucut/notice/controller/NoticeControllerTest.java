package com.harucut.notice.controller;

import com.harucut.terms.service.TermsConsentGuard;
import com.harucut.common.response.PageResponse;
import com.harucut.notice.dto.NoticeResponse;
import com.harucut.notice.service.NoticeService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@WebMvcTest(NoticeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class NoticeControllerTest {

    // WebMvcConfig 의 약관 동의 인터셉터가 요구한다 — 기본값 false 라 동의 강제는 걸리지 않는다
    @MockitoBean
    private TermsConsentGuard termsConsentGuard;

    @Autowired
    private MockMvcTester mockMvc;

    @MockitoBean
    private NoticeService noticeService;

    @Test
    @DisplayName("목록은 공통 봉투 안에 PageResponse 구조로 나온다")
    void listShape() {
        given(noticeService.getPublishedNotices(0, 10)).willReturn(pageResponse());

        assertThat(mockMvc.get().uri("/api/notices"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-000"))
                .hasPathSatisfying("$.status", s -> assertThat(s).isEqualTo(200))
                .hasPathSatisfying("$.data.totalElements", v -> assertThat(v).isEqualTo(12))
                .hasPathSatisfying("$.data.totalPages", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.data.number", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.data.size", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.data.content[0].publicId", v -> assertThat(v).isEqualTo("aB3dE7fG9h"));
    }

    @Test
    @DisplayName("성공 응답에는 message 키가 없다")
    void noMessageOnSuccess() {
        given(noticeService.getPublishedNotices(0, 10)).willReturn(pageResponse());

        assertThat(mockMvc.get().uri("/api/notices"))
                .bodyJson().doesNotHavePath("$.message");
    }

    @Test
    @DisplayName("page/size를 생략하면 0과 10이 서비스로 넘어간다")
    void defaultPageParams() {
        given(noticeService.getPublishedNotices(0, 10)).willReturn(pageResponse());

        assertThat(mockMvc.get().uri("/api/notices")).hasStatusOk();

        then(noticeService).should().getPublishedNotices(0, 10);
    }

    @Test
    @DisplayName("page가 숫자가 아니면 GEN-005를 반환하고 서비스를 호출하지 않는다")
    void nonNumericPage() {
        assertThat(mockMvc.get().uri("/api/notices?page=abc"))
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("GEN-005"));

        then(noticeService).shouldHaveNoInteractions();
    }

    private PageResponse<NoticeResponse> pageResponse() {
        return new PageResponse<>(
                List.of(new NoticeResponse("aB3dE7fG9h", "서비스 점검 안내", "본문", true,
                        LocalDateTime.of(2026, 7, 22, 10, 0))),
                12L, 2, 0, 10
        );
    }
}