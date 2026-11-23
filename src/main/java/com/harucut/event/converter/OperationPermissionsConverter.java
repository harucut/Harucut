package com.harucut.event.converter;

import com.harucut.event.enums.OperationPermission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

// 운영 권한 집합 ↔ 쉼표 목록(PhotoPermissionsConverter 와 같은 규칙)
@Converter
public class OperationPermissionsConverter implements AttributeConverter<Set<OperationPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<OperationPermission> operations) {
        if (operations == null || operations.isEmpty()) {
            return "";
        }
        return EnumSet.copyOf(operations).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<OperationPermission> convertToEntityAttribute(String value) {
        EnumSet<OperationPermission> operations = EnumSet.noneOf(OperationPermission.class);
        if (value == null || value.isBlank()) {
            return operations;
        }
        Arrays.stream(value.split(",")).map(String::strip).filter(name -> !name.isEmpty())
                .map(OperationPermission::valueOf).forEach(operations::add);
        return operations;
    }
}
