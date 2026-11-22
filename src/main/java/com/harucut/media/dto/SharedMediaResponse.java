package com.harucut.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "공유 링크로 연 사진. **URL 은 몇 분만 열린다** — 페이지에 박지 말고 쓸 때마다 다시 받는다")
public record SharedMediaResponse(

        @Schema(description = "저장될 파일 이름 — 주인이 정한 표시 이름이다", example = "나의 기록.jpg")
        String displayName,

        @Schema(description = "사진을 만든 시각", example = "2026-08-03T10:20:30")
        LocalDateTime createdAt,

        @Schema(description = "**화면에 띄울** URL(`<img src>`·og:image). 원본 해상도 plain GET, 짧게 열린다(기본 5분)",
                example = "https://harucut-bucket.s3.ap-northeast-2.amazonaws.com/.../job-12.jpg?X-Amz-Expires=300&X-Amz-Signature=...")
        String imageUrl,

        @Schema(description = """
                **저장 버튼** URL. `Content-Disposition: attachment` 라 브라우저가 바로 저장한다.
                짧게 열린다(기본 5분). `<img src>` 에 넣지 말 것.""",
                example = "https://harucut-bucket.s3.ap-northeast-2.amazonaws.com/.../job-12.jpg?response-content-disposition=attachment...")
        String downloadUrl
) {
}
