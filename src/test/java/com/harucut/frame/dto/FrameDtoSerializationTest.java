package com.harucut.frame.dto;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.enums.ComponentType;
import com.harucut.frame.enums.FrameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// wire 계약 고정 — 앱 설정 그대로의 매퍼로 검증한다 (docs/testing.md)
@JsonTest
@ActiveProfiles("test")
@DisplayName("프레임 DTO 직렬화")
class FrameDtoSerializationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("요청 역직렬화")
    class RequestDeserialization {

        @Test
        @DisplayName("docs 예시 형태의 전체 요청이 그대로 읽힌다")
        void fullRequest() {
            String json = """
                    {
                      "title": "봄 여행 4컷",
                      "description": "벚꽃 배경의 여행 프레임",
                      "previewKey": "uploads/users/AbCdEf12Gh/webm/preview.png",
                      "frameType": "CLASSIC",
                      "canvasWidth": 2000,
                      "canvasHeight": 6000,
                      "background": { "type": "COLOR", "value": "#FFE4E1" },
                      "components": [
                        {
                          "id": "comp-1",
                          "type": "PHOTO",
                          "source": "uploads/users/AbCdEf12Gh/components/photo1.png",
                          "x": 120.5, "y": 220.0,
                          "width": 360.0, "height": 480.0,
                          "scale": 1.0, "rotation": 0.0,
                          "zIndex": 1,
                          "styleJson": { "borderRadius": 8 }
                        }
                      ]
                    }
                    """;

            FrameCreateRequest request = objectMapper.readValue(json, FrameCreateRequest.class);

            assertThat(request.title()).isEqualTo("봄 여행 4컷");
            assertThat(request.frameType()).isEqualTo(FrameType.CLASSIC);
            assertThat(request.background()).isEqualTo(new BackgroundAttributes.Color("#FFE4E1"));
            FrameCreateRequest.ComponentRequest component = request.components().get(0);
            assertThat(component.type()).isEqualTo(ComponentType.PHOTO);
            assertThat(component.zIndex()).isEqualTo(1);
            assertThat(component.styleJson()).isEqualTo(Map.of("borderRadius", 8));
        }

        @Test
        @DisplayName("소문자 zindex로 보내도 인식된다 — 하위 호환 alias")
        void lowercaseZindexAlias() {
            String json = """
                    { "type": "STICKER", "source": "/static/stickers/heart.png", "zindex": 7 }
                    """;

            FrameCreateRequest.ComponentRequest component =
                    objectMapper.readValue(json, FrameCreateRequest.ComponentRequest.class);

            assertThat(component.zIndex()).isEqualTo(7);
        }

        @Test
        @DisplayName("좌표·회전·zIndex를 안 보내면 0이다 — 컴팩트 생성자가 소유한 기본값")
        void missingCoordinatesDefaultToZero() {
            String json = """
                    { "type": "TEXT", "source": "봄 여행" }
                    """;

            FrameCreateRequest.ComponentRequest component =
                    objectMapper.readValue(json, FrameCreateRequest.ComponentRequest.class);

            assertThat(component.x()).isZero();
            assertThat(component.y()).isZero();
            assertThat(component.rotation()).isZero();
            assertThat(component.zIndex()).isZero();
            // 래퍼 필드는 "안 보냈다"는 사실을 null로 보존한다 — 보정은 응답 조립에서만
            assertThat(component.width()).isNull();
            assertThat(component.height()).isNull();
            assertThat(component.scale()).isNull();
        }

        @Test
        @DisplayName("description이 없으면 descriptionOrEmpty가 빈 문자열을 준다")
        void descriptionFallsBackToEmpty() {
            FrameCreateRequest request = new FrameCreateRequest(
                    "제목", null, "uploads/p.png", FrameType.CLASSIC, 0, 0,
                    new BackgroundAttributes.Color("#FFF"), null, null);

            assertThat(request.descriptionOrEmpty()).isEmpty();
        }
    }

    @Nested
    @DisplayName("응답 직렬화")
    class ResponseSerialization {

        @Test
        @DisplayName("컴포넌트의 직렬화 이름이 정확히 zIndex다 — 소문자 zindex가 아니다")
        void zIndexSerializedName() {
            FrameResponse.ComponentResponse response = new FrameResponse.ComponentResponse(
                    10L, ComponentType.PHOTO, "https://presigned.example/photo.png",
                    "uploads/users/AbCdEf12Gh/components/photo1.png",
                    120.5, 220.0, 360.0, 480.0, 1.0, 0.0, 3, Map.of(),
                    false, null, null, null, null);

            String json = objectMapper.writeValueAsString(response);

            assertThat(json).contains("\"zIndex\":3").doesNotContain("zindex");
        }
    }
}
