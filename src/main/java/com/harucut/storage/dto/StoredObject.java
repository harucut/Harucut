package com.harucut.storage.dto;

// HeadObject 결과 중 서버가 판단에 쓰는 것만
public record StoredObject(long size, String contentType) {
}
