package com.harucut.draft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "frame.draft")
public record FrameDraftProperties(
        // 직렬화한 문서의 UTF-8 바이트 상한. 사진·배경은 dataURL 로 넣지 말고 자산(assetId)으로 참조한다
        @DefaultValue("262144") int maxBytes
) {
}
