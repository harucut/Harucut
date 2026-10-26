package com.harucut.admin.dto;

import com.harucut.event.enums.EventStatus;
import com.harucut.workspace.enums.WorkspaceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

// 플랫폼 CMS 의 공간·행사 찾기 — 계약·수동 부여 때 id 를 손으로 적지 않게
public final class AdminSearchDtos {

    private AdminSearchDtos() {
    }

    @Schema(name = "AdminWorkspaceItem", description = "조직 공간 검색 결과")
    public record WorkspaceItem(String workspaceId, String name, WorkspaceType type,
                                @Schema(description = "활성 OWNER 의 이메일") List<String> ownerEmails, OffsetDateTime createdAt) {
    }

    @Schema(name = "AdminEventItem", description = "행사 검색 결과 — EVENT 범위 계약에 행사·공간 id 가 둘 다 필요하다")
    public record EventItem(String eventId, String slug, String name, EventStatus status, String workspaceId,
                            String workspaceName, OffsetDateTime createdAt) {
    }
}
