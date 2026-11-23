package com.harucut.workspace.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.workspace.dto.WorkspaceCreateRequest;
import com.harucut.workspace.dto.WorkspaceResponse;
import com.harucut.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "공간")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @Operation(
            summary = "접근 가능한 공간 목록",
            description = """
                    프레임·자산·초안이 속하는 공간. P1 에서는 **개인 공간 하나**가 나온다.
                    처음 부르면 개인 공간이 없던 사용자에게도 만들어진다.
                    """)
    @GetMapping
    public Response<List<WorkspaceResponse>> listMine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Response.ok(workspaceService.listMine(principal.publicId()));
    }

    @Operation(
            summary = "조직 공간 만들기",
            description = """
                    행사를 운영할 공유 공간. 만든 사람이 OWNER 가 된다. **행사는 조직 공간에만 만들 수 있다** —
                    개인 공간은 회원 탈퇴 때 통째로 지워지기 때문이다. 행사에 쓸 프레임도 이 공간에 만든다(`workspaceId`).
                    """)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response<WorkspaceResponse> createOrganization(@AuthenticationPrincipal AuthenticatedUser principal,
                                                          @RequestBody @Valid WorkspaceCreateRequest request) {
        return Response.ok(workspaceService.createOrganization(principal.publicId(), request.name()));
    }
}
