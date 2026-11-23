package com.harucut.asset.exception;

import com.harucut.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AssetErrorCode implements ErrorCode {

    // 아직 안 올렸거나 PUT 이 실패했다. REJECTED 로 기록하지 않는다 — 올리는 중일 수 있다
    OBJECT_NOT_UPLOADED("ASSET-001", HttpStatus.CONFLICT, "The uploaded object was not found."),
    SIZE_MISMATCH("ASSET-002", HttpStatus.UNPROCESSABLE_CONTENT, "The uploaded object size does not match or exceeds the limit."),
    INVALID_IMAGE("ASSET-003", HttpStatus.UNPROCESSABLE_CONTENT, "The uploaded object is not a valid image of the declared type."),
    TOO_MANY_PIXELS("ASSET-004", HttpStatus.UNPROCESSABLE_CONTENT, "The image exceeds the pixel limit."),
    PURPOSE_NOT_ALLOWED("ASSET-005", HttpStatus.BAD_REQUEST, "This purpose cannot be uploaded through this endpoint."),
    // 검증 자리가 모자라다 — 잠시 뒤 같은 complete 를 다시 부르면 된다
    VERIFY_BUSY("ASSET-006", HttpStatus.SERVICE_UNAVAILABLE, "Asset verification is busy. Retry shortly.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String message;

    public static AssetErrorCode fromCode(String code) {
        for (AssetErrorCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("알 수 없는 자산 오류 코드: " + code);
    }
}
