package com.harucut.storage.service;

import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;

import com.harucut.storage.dto.PresignedUploadResponse;
import com.harucut.storage.enums.ContentType;
import com.harucut.storage.enums.UploadType;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

public interface FileStorageService {

    PresignedUploadResponse generatePresignedUploadUrl(UploadType type, String filename,
                                                       ContentType contentType, long fileSize,
                                                       String publicId);

    String generatePresignedGetUrl(String key);

    String generatePresignedDownloadUrl(String key, String downloadFileName);

    /** 큰 객체를 흘려 읽는다 — 닫는 것은 호출자 몫 */
    InputStream openStream(String key);

    /** 큰 객체를 흘려 올린다(S3 multipart). close 가 완료, 실패하면 abort 를 부른다 */
    S3MultipartOutputStream openMultipartUpload(String key, String contentType);

    /** 파일 이름(RFC 5987)을 붙인 짧은 다운로드 URL — 사진함·ZIP 처럼 수명을 따로 정하는 곳 */
    String generatePresignedDownloadUrl(String key, String downloadFileName, Duration ttl);

    void delete(String key);

    // 서버가 직접 읽고 쓰는 경로 — 네컷 합성이 원본을 내려받고 결과를 올릴 때 쓴다.
    // presigned와 달리 사용자를 거치지 않으므로 소유권 검증은 호출자 책임이다
    byte[] downloadBytes(String key);

    void uploadBytes(String key, byte[] bytes, String contentType);

    /**
     * 서버가 정한 key 에 대한 PUT URL. contentType·size 는 서명에 들어가 S3 가 강제한다.
     * conditional 이면 If-None-Match: * 도 서명에 넣는다 — 이미 객체가 있으면 S3 가 412 로 거부한다.
     */
    PresignedPut presignPut(String key, String mimeType, long size, Duration ttl, boolean conditional);

    String generatePresignedGetUrl(String key, Duration ttl);

    /**
     * 객체가 없으면 empty. 권한 오류·일시 장애는 예외로 그대로 올린다 — 모든 HEAD 실패를 "미업로드"로 단정하지 않는다.
     */
    Optional<StoredObject> head(String key);

    /**
     * 저장된 객체의 SHA-256 체크섬(S3 checksumSHA256, base64) — 업로드 때 S3 가 검증·저장한 값이다(ChecksumMode).
     * 객체가 없거나 그 체크섬 없이 올라간 객체면 비어 있다
     */
    Optional<String> sha256Checksum(String key);
}
