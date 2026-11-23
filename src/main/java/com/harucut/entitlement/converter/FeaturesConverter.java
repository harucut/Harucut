package com.harucut.entitlement.converter;

import com.harucut.entitlement.enums.Feature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

// 기능 집합 ↔ 쉼표 목록(enum 순서 고정)
@Converter
public class FeaturesConverter implements AttributeConverter<Set<Feature>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Feature> features) {
        return features == null || features.isEmpty() ? ""
                : EnumSet.copyOf(features).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<Feature> convertToEntityAttribute(String value) {
        EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
        if (value != null && !value.isBlank()) {
            Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Feature::valueOf).forEach(features::add);
        }
        return features;
    }
}
