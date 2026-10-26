package com.harucut.asset.dto;

import com.harucut.asset.enums.AssetPurpose;
import com.harucut.storage.dto.PresignedUploadRequest;
import com.harucut.storage.enums.ContentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "자산 업로드 의도")
public record UploadIntentRequest(

        @NotNull
        @Schema(description = """
                자산 용도. 이 경로는 `FRAME_ASSET`(배경·PHOTO·STICKER·로고) · `TEXT_RENDER`(구운 글자 PNG) · `PROFILE` ·
                `PAGE_THEME_ASSET`(행사 테마 로고·배경 — workspaceId 에 조직 공간) 을 받는다.
                `CAPTURE_INPUT` 은 참가자 경로 전용이라 400 `ASSET-005`.""",
                example = "FRAME_ASSET", requiredMode = Schema.RequiredMode.REQUIRED)
        AssetPurpose purpose,

        @NotNull
        @Schema(description = """
                파일 형식(enum 이름). `FRAME_ASSET`·`PAGE_THEME_ASSET` 은 PNG·JPEG, `TEXT_RENDER` 는 PNG, `PROFILE` 은 PNG·JPEG·GIF.
                용도에 맞지 않으면 415 `GEN-051`.""",
                example = "PNG", requiredMode = Schema.RequiredMode.REQUIRED)
        ContentType contentType,

        @NotNull @Positive @Max(PresignedUploadRequest.MAX_FILE_SIZE)
        @Schema(description = "바이트 크기. 서명에 들어가 S3 가 강제한다. 10MB 이하", example = "245760",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long fileSize,

        @NotBlank
        @Schema(description = "확장자 검사용 파일명. 저장 key 에는 쓰지 않는다", example = "background.png",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String filename,

        @Schema(description = "올릴 공간의 public id. 생략하면 개인 공간", example = "Ws1aB2cD3eF4")
        String workspaceId
) {
}
