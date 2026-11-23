package com.harucut.frame.converter;

import com.harucut.frame.cell.PhotoCell;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

// 자유 사진 칸 4개 ↔ JSON 배열. null 은 고정 레이아웃. 누끼 토글과 달리 장식이 아니라 레이아웃이라
// 깨진 값을 조용히 고정 레이아웃으로 바꾸지 않는다 — 다른 모양으로 합성되는 것보다 오류가 낫다
@Converter
@RequiredArgsConstructor
public class PhotoCellsConverter implements AttributeConverter<List<PhotoCell>, String> {

    private final ObjectMapper objectMapper;

    @Override
    public String convertToDatabaseColumn(List<PhotoCell> attribute) {
        return attribute == null ? null : objectMapper.writeValueAsString(attribute);
    }

    @Override
    public List<PhotoCell> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<List<PhotoCell>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("자유 사진 칸 JSON 을 읽을 수 없다: " + dbData, e);
        }
    }
}
