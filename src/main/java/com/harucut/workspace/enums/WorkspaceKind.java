package com.harucut.workspace.enums;

/**
 * 조직 공간의 종류(사용자 결정, 2026-11-03). 사진을 누가 보는가를 가른다 — 운영 권한은 같다.
 * <ul>
 *   <li>{@code HOST}(기본): 사용자가 직접 만든 공간(결혼식·파티 주최자). 행사를 만든 OWNER·ADMIN 이 사진 권한 네 개를 자동으로 받고,
 *       자기 초대를 자기가 수락할 수 있다.</li>
 *   <li>{@code ENTERPRISE}: 업체 공간(결혼식장 등). 공간 멤버는 이 공간 행사의 사진을 보지 않는다 — 사진 권한 행이 있어도 판정에서 빠진다
 *       ({@code EventAccessPolicy}). 고객(EventGrant)만 받은 권한대로 본다. 플랫폼 관리자가 정한다({@code AdminWorkspaceService}).</li>
 * </ul>
 */
public enum WorkspaceKind {
    HOST, ENTERPRISE
}
