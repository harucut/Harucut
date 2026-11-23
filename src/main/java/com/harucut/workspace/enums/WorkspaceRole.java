package com.harucut.workspace.enums;

public enum WorkspaceRole {
    OWNER, ADMIN, DESIGNER, VIEWER,
    // 담당 운영자 — 공간 전체 권한은 없고 배정받은 행사·프로젝트만 운영한다(ADR-0009 §3)
    OPERATOR
}
