package com.harucut.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 읽기 복제 대비 (ADR-0009 §7-3) — 클래스에 쓰기 트랜잭션이 걸린 서비스의 조회 메서드는 @Transactional(readOnly = true) 를 단다.
 * 복제 DB 라우팅이 생기면 readOnly 가 그 기준이 된다. 이름은 조회처럼 보여도 실제로 쓰는 메서드는 이유와 함께 예외로 둔다.
 */
@DisplayName("읽기 전용 트랜잭션 구분")
class ReadOnlyTransactionConventionTest {

    private static final Pattern READ_NAME = Pattern.compile(
            "^(get|list|find|mine|search|view|preview|count|summary|usage|history|me)([A-Z].*)?$");

    // 조회 이름이지만 쓴다 — readOnly 로 두면 그 쓰기가 조용히 빠진다
    private static final Map<String, String> WRITES = Map.of(
            "BoothService.me", "기기의 마지막 접속 시각(seen)을 남긴다",
            "WorkspaceService.listMine", "개인 공간이 없으면 만든다");

    @Test
    @DisplayName("쓰기 트랜잭션 서비스의 조회 메서드는 readOnly — 쓰는 것은 이유를 적어 예외로")
    void readMethodsAreReadOnly() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.harucut")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            Transactional classLevel = AnnotatedElementUtils.findMergedAnnotation(type, Transactional.class);
            if (classLevel == null || classLevel.readOnly()) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || !READ_NAME.matcher(method.getName()).matches()) {
                    continue;
                }
                checked++;
                String name = type.getSimpleName() + "." + method.getName();
                Transactional own = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                if ((own == null || !own.readOnly()) && !WRITES.containsKey(name)) {
                    missing.add(name);
                }
            }
        }
        assertThat(checked).isGreaterThan(30);
        assertThat(missing).isEmpty();
    }
}
