package com.harucut.export.service;

/** 커밋 뒤 워커에 넘길 묶음 작업 id */
public record ExportRequestedEvent(Long exportJobId) {
}
