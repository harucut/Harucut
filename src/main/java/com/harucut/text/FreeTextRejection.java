package com.harucut.text;

/** 문구 검증 실패의 종류 — PART-008 의 data.reason. */
public enum FreeTextRejection {
    EMPTY, TOO_LONG, TOO_MANY_LINES, UNSUPPORTED_CHARACTER, STYLE_NOT_OFFERED, DOES_NOT_FIT
}
