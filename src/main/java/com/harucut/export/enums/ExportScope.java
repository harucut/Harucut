package com.harucut.export.enums;

// 묶음 범위(ADR-0009 §6) — EVENT 는 행사 하나, PROJECT·WORKSPACE 는 요청에 명시한 여러 행사
public enum ExportScope {
    EVENT, PROJECT, WORKSPACE
}
