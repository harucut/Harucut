package com.harucut.config.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * springdoc 은 스키마를 클래스 "단순 이름"으로 등록한다. 패키지가 다른 두 DTO 의 이름이 같으면 하나로 합쳐져
 * 한쪽 API 의 계약이 다른 쪽 모양으로 조용히 바뀐다 — 실제로 개인 합성 ComposeRequest 가 행사 합성 모양으로 덮였다.
 * 스냅샷 테스트는 산출물을 자기 자신과 비교하므로 이것을 못 잡는다. 여기서 컨트롤러가 주고받는 타입을 전부 훑어
 * "스키마 이름 하나 = 클래스 하나"를 강제한다.
 */
@DisplayName("OpenAPI 스키마 이름 — 클래스 하나에만 대응")
class SchemaNameUniquenessTest {

    @Test
    @DisplayName("컨트롤러가 주고받는 타입의 스키마 이름이 겹치지 않는다")
    void schemaNamesAreUnique() throws Exception {
        Map<String, Set<String>> classesByName = new TreeMap<>();
        Set<Class<?>> seen = new HashSet<>();

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.harucut")) {
            Class<?> controller = ClassUtils.forName(definition.getBeanClassName(), getClass().getClassLoader());
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                walk(method.getGenericReturnType(), classesByName, seen);
                for (Type parameter : method.getGenericParameterTypes()) {
                    walk(parameter, classesByName, seen);
                }
            }
        }

        assertThat(classesByName).as("우리 DTO 를 하나도 못 찾았다 — 탐색이 고장났다").isNotEmpty();
        Map<String, Set<String>> collisions = new TreeMap<>();
        classesByName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                collisions.put(name, classes);
            }
        });
        assertThat(collisions).as("같은 스키마 이름의 서로 다른 클래스 — @Schema(name = ...) 로 구분할 것").isEmpty();
    }

    private void walk(Type type, Map<String, Set<String>> classesByName, Set<Class<?>> seen) {
        switch (type) {
            case ParameterizedType parameterized -> {
                walk(parameterized.getRawType(), classesByName, seen);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    walk(argument, classesByName, seen);
                }
            }
            case GenericArrayType array -> walk(array.getGenericComponentType(), classesByName, seen);
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    walk(bound, classesByName, seen);
                }
            }
            case Class<?> clazz -> visit(clazz, classesByName, seen);
            default -> {
            }
        }
    }

    private void visit(Class<?> clazz, Map<String, Set<String>> classesByName, Set<Class<?>> seen) {
        if (clazz.isArray()) {
            visit(clazz.getComponentType(), classesByName, seen);
            return;
        }
        if (!clazz.getName().startsWith("com.harucut.") || !seen.add(clazz)) {
            return;
        }
        classesByName.computeIfAbsent(schemaName(clazz), key -> new TreeSet<>()).add(clazz.getName());
        if (clazz.isEnum()) {
            return;
        }
        // sealed 인터페이스의 구현(배경 COLOR·IMAGE 같은 다형 스키마)도 스키마로 나간다
        if (clazz.isSealed()) {
            for (Class<?> permitted : clazz.getPermittedSubclasses()) {
                visit(permitted, classesByName, seen);
            }
        }
        for (Class<?> current = clazz; current != null && current.getName().startsWith("com.harucut."); current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    walk(field.getGenericType(), classesByName, seen);
                }
            }
        }
    }

    private static String schemaName(Class<?> clazz) {
        Schema schema = clazz.getAnnotation(Schema.class);
        return schema != null && !schema.name().isBlank() ? schema.name() : clazz.getSimpleName();
    }
}
