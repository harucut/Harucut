package com.harucut.frame.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.frame.converter.StyleMapConverter;
import com.harucut.frame.enums.ComponentType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Entity
@Table(name = "frame_component")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FrameComponent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_component_id")
    private Long id;

    @Column(nullable = false, length = 1024)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ComponentType type;

    private double x;
    private double y;

    private Double width;
    private Double height;
    private Double scale;

    private double rotation;

    @Column(name = "z_index")
    private int zIndex;

    // TEXT 전용: 프론트가 출력 해상도의 투명 PNG로 구워 올린 텍스트 층의 S3 key.
    // 편집기는 source(글자)+style로 다시 그리므로 응답에는 안 나가고, 합성 파이프라인만 읽는다
    @Column(name = "rendered_key", length = 512)
    private String renderedKey;

    // 편집기 잠금 — 저장·재열기용. 인가 근거가 아니다(행사 편집 정책은 발행본이 강제한다)
    @Column(nullable = false)
    private boolean locked;

    // source 를 자산으로 받았을 때 그 자산(FK). 기존 key 문자열 컴포넌트는 NULL
    @Column(name = "asset_id")
    private Long assetId;

    // TEXT 의 구운 PNG 를 자산으로 받았을 때(FK)
    @Column(name = "rendered_asset_id")
    private Long renderedAssetId;

    @Convert(converter = StyleMapConverter.class)
    @Column(name = "style_json", length = 4000)
    private Map<String, Object> style;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_id")
    private Frame frame;

    @Builder
    private FrameComponent(String source, ComponentType type, double x, double y,
                           Double width, Double height, Double scale, double rotation,
                           int zIndex, String renderedKey, Map<String, Object> style,
                           boolean locked, Long assetId, Long renderedAssetId) {
        this.locked = locked;
        this.assetId = assetId;
        this.renderedAssetId = renderedAssetId;
        this.source = source;
        this.type = type;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.rotation = rotation;
        this.zIndex = zIndex;
        this.renderedKey = renderedKey;
        this.style = style == null ? Map.of() : style;
    }

    // Frame.addComponent만 부르도록 package-private — 외부 패키지에서 연관관계를 임의로 끊을 수 없다
    void assignFrame(Frame frame) {
        this.frame = frame;
    }
}
