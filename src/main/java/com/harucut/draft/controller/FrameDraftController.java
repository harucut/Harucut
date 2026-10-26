package com.harucut.draft.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.draft.dto.DraftCreateRequest;
import com.harucut.draft.dto.DraftResponse;
import com.harucut.draft.dto.DraftUpdateRequest;
import com.harucut.draft.service.FrameDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "프레임 초안")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/frame-drafts")
public class FrameDraftController {

    private final FrameDraftService frameDraftService;

    @Operation(summary = "초안 만들기", description = "revision 1 로 시작한다. 이후 저장은 PUT 에 마지막 revision 을 보낸다.")
    @ApiErrors({
            "DRAFT-002: 문서가 256KB 를 넘는다",
            "FRAME-005: assetIds 에 쓸 수 없는 자산(남의 것·미검증)",
            "GEN-031: frameId·workspaceId 가 없거나 내 것이 아님"
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<DraftResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @RequestBody @Valid DraftCreateRequest request) {
        return Response.ok(frameDraftService.create(principal.publicId(), request));
    }

    @Operation(summary = "내 초안 목록", description = "최근 저장 순. `document` 는 빠진다.")
    @ApiErrors("GEN-005: frameId 가 숫자도 new 도 아니다")
    @GetMapping
    public Response<List<DraftResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "숫자면 그 프레임의 초안, `new` 면 새 프레임 초안만, 생략하면 전부", example = "new")
            @RequestParam(required = false) String frameId) {
        return Response.ok(frameDraftService.list(principal.publicId(), frameId));
    }

    @Operation(summary = "초안 하나")
    @ApiErrors("GEN-031: 없는 초안이거나 내 것이 아님")
    @GetMapping("/{draftId}")
    public Response<DraftResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Parameter(description = "초안 public id") @PathVariable String draftId) {
        return Response.ok(frameDraftService.get(principal.publicId(), draftId));
    }

    @Operation(
            summary = "초안 저장",
            description = """
                    `revision` 이 서버와 같을 때만 저장하고 1 올린다. 다르면 **409 `DRAFT-001`** 이고
                    `data` 에 서버의 `{ revision, updatedAt }` 이 실린다 — 다시 불러오거나 그 revision 으로 덮어쓴다.
                    """)
    @ApiErrors({
            "DRAFT-001: 다른 기기가 먼저 저장했다",
            "DRAFT-002: 문서가 256KB 를 넘는다",
            "FRAME-005: assetIds 에 쓸 수 없는 자산",
            "GEN-031: 없는 초안이거나 내 것이 아님"
    })
    @PutMapping("/{draftId}")
    public Response<DraftResponse> update(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @Parameter(description = "초안 public id") @PathVariable String draftId,
                                          @RequestBody @Valid DraftUpdateRequest request) {
        return Response.ok(frameDraftService.update(principal.publicId(), draftId, request));
    }

    @Operation(summary = "초안 삭제", description = "없거나 이미 지운 초안은 404 다.")
    @ApiErrors("GEN-031: 없는 초안이거나 내 것이 아님")
    @DeleteMapping("/{draftId}")
    public Response<Void> delete(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Parameter(description = "초안 public id") @PathVariable String draftId) {
        frameDraftService.delete(principal.publicId(), draftId);
        return Response.ok();
    }
}
