package com.harucut.common.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MailTemplate")
class MailTemplateTest {

    @Test
    @DisplayName("두 메일 템플릿 모두 {{code}} 자리가 있고, 그 자리에 코드가 들어간다")
    void rendersCodeIntoTemplates() {
        for (String path : List.of("templates/mail/verification-code.html", "templates/mail/password-reset-code.html")) {
            String html = MailTemplate.render(path, Map.of("code", "A7K2M9"));

            assertThat(html).as(path).contains("A7K2M9").doesNotContain("{{code}}").doesNotContain("th:text");
        }
    }

    @Test
    @DisplayName("없는 템플릿이면 바로 던진다 — 빈 메일을 보내지 않는다")
    void missingTemplateFails() {
        assertThatThrownBy(() -> MailTemplate.render("templates/mail/nope.html", Map.of()))
                .hasMessageContaining("nope.html");
    }
}
