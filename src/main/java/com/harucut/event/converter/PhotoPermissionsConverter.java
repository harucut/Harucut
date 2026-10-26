package com.harucut.event.converter;

import com.harucut.event.enums.PhotoPermission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

// 권한 집합 ↔ 쉼표 목록. 순서는 enum 순서로 고정해 같은 집합이 같은 문자열이 되게 한다
@Converter
public class PhotoPermissionsConverter implements AttributeConverter<Set<PhotoPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<PhotoPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        return EnumSet.copyOf(permissions).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<PhotoPermission> convertToEntityAttribute(String value) {
        EnumSet<PhotoPermission> permissions = EnumSet.noneOf(PhotoPermission.class);
        if (value == null || value.isBlank()) {
            return permissions;
        }
        Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty())
                .map(PhotoPermission::valueOf).forEach(permissions::add);
        return permissions;
    }
}
