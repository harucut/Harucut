package com.harucut.common.mail;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메일 본문 — classpath 의 HTML 한 장에서 {@code {{이름}}} 자리만 값으로 바꾼다. 자리가 코드 하나뿐이라 템플릿 엔진을 두지 않는다.
 * 값은 서버가 만든 인증 코드처럼 믿을 수 있는 것만 넣는다 — 이스케이프하지 않는다
 */
public final class MailTemplate {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private MailTemplate() {
    }

    public static String render(String path, Map<String, String> values) {
        String html = CACHE.computeIfAbsent(path, MailTemplate::load);
        for (Map.Entry<String, String> value : values.entrySet()) {
            html = html.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        return html;
    }

    private static String load(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("메일 템플릿을 읽을 수 없다: " + path, e);
        }
    }
}
