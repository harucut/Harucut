package com.harucut.frame.attributes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BackgroundAttributes.Color.class, name = "COLOR"),
        @JsonSubTypes.Type(value = BackgroundAttributes.Image.class, name = "IMAGE")
})
public sealed interface BackgroundAttributes permits BackgroundAttributes.Color, BackgroundAttributes.Image {

    record Color(String value) implements BackgroundAttributes {

        public Color {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("배경 색상 value는 비어 있을 수 없다");
            }
        }
    }

    record Image(
            String key,
            Double opacity,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String url,
            // 배경을 자산으로 받았을 때의 자산 public id(저장·응답용). 합성기는 key 만 쓴다
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String assetId,
            // 배치 — 없으면 지금까지의 동작(COVER, 중앙, 전체)과 같다. 값이 없을 때 JSON 에 남기지 않아
            // 이 필드를 모르는 렌더러·옛 스냅샷과 같은 모양을 유지한다
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Fit fit,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Double focalX,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Double focalY,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Crop crop
    ) implements BackgroundAttributes {
        public Image {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("배경 이미지 key는 비어 있을 수 없다");
            }
            if (opacity == null) {
                throw new IllegalArgumentException("opacity는 필수다");
            }
            requireUnit("focalX", focalX);
            requireUnit("focalY", focalY);
        }

        // 배치 필드가 없던 시절의 생성자 — 기본 배치(COVER·중앙·전체)
        public Image(String key, Double opacity, String url) {
            this(key, opacity, url, null, null, null, null, null);
        }

        public Image withUrl(String url) {
            return new Image(key, opacity, url, assetId, fit, focalX, focalY, crop);
        }

        public Image withKey(String key, String assetId) {
            return new Image(key, opacity, url, assetId, fit, focalX, focalY, crop);
        }

        public Fit effectiveFit() {
            return fit == null ? Fit.COVER : fit;
        }

        public double effectiveFocalX() {
            return focalX == null ? 0.5 : focalX;
        }

        public double effectiveFocalY() {
            return focalY == null ? 0.5 : focalY;
        }
    }

    // COVER: 캔버스를 빈틈없이 채우고 넘치는 쪽을 자른다. CONTAIN: 이미지 전체가 보이게 맞추고 남는 쪽을 비운다
    enum Fit {
        COVER, CONTAIN
    }

    // 원본 이미지에서 쓸 영역 — 0~1 로 정규화한 좌상단과 크기
    record Crop(double x, double y, double width, double height) {
        public Crop {
            requireUnit("crop.x", x);
            requireUnit("crop.y", y);
            if (!(width > 0 && width <= 1) || !(height > 0 && height <= 1)) {
                throw new IllegalArgumentException("crop 의 width·height 는 0 초과 1 이하여야 한다");
            }
            if (x + width > 1 + 1e-9 || y + height > 1 + 1e-9) {
                throw new IllegalArgumentException("crop 이 원본 밖으로 나간다");
            }
        }
    }

    private static void requireUnit(String name, Double value) {
        if (value != null && !(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " 는 0 이상 1 이하여야 한다");
        }
    }
}
