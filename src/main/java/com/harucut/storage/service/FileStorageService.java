package com.harucut.storage.service;

import com.harucut.storage.dto.PresignedPut;
import com.harucut.storage.dto.StoredObject;

import com.harucut.storage.dto.PresignedUploadResponse;
import com.harucut.storage.enums.ContentType;
import com.harucut.storage.enums.UploadType;

import java.time.Duration;
import java.util.Optional;

public interface FileStorageService {

    PresignedUploadResponse generatePresignedUploadUrl(UploadType type, String filename,
                                                       ContentType contentType, long fileSize,
                                                       String publicId);

    String generatePresignedGetUrl(String key);

    String generatePresignedDownloadUrl(String key, String downloadFileName);

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
}
