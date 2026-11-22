package com.harucut.frame.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import com.harucut.frame.dto.FrameCreateRequest;
import com.harucut.frame.dto.FrameResponse;
import com.harucut.frame.service.FrameService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "프레임")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/user/frame")
public class FrameController {

    private final FrameService frameService;

    @Operation(
            summary = "프레임 생성",
            description = """
                    ⚠️ **BASIC 은 프레임을 하나도 저장할 수 없다.** 보관 한도가 0 이라 무조건 `SUBS-003` 이다.
                    저장은 유료 기능이고, 만들어서 촬영까지는 무료로 된다. 저장 버튼을 누르기 전에
                    사용량 API 로 한도를 확인해 업그레이드를 안내하는 편이 낫다 (PLUS 3개 · PRO 무제한).

                    응답으로 만들어진 프레임 전체가 돌아온다 — `frameId` 를 바로 쓸 수 있어 목록을 다시 받을 필요가 없다.

                    `previewKey`·컴포넌트 `source`·배경 `key` 는 presigned URL 을 통째로 보내도
                    서버가 순수 key 로 정규화한다.
                    """)
    @ApiErrors({
            "SUBS-003: 요금제의 프레임 보관 한도 초과 (BASIC 은 항상)",
            "GEN-006: background.type 이 COLOR·IMAGE 가 아님",
            "GEN-031: 토큰은 유효한데 그 계정이 사라짐"
    })
    @PostMapping
    public Response<FrameResponse> createFrame(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @RequestBody @Valid FrameCreateRequest request) {
        return Response.ok(frameService.createFrame(principal.publicId(), request));
    }

    @Operation(
            summary = "프레임 목록",
            description = """
                    **내 프레임(최신순) 다음에 시스템 프레임(최신순)** 순서로 한 배열에 담겨 온다.
                    `isSystem` 으로 구분한다. 시스템 프레임은 요금제 제한을 받지 않는다.

                    시스템 프레임에는 **기본 프레임이 `frameType` 마다 항상 하나 있다** — 제목 세로·가로·네모·즉석사진 4컷,
                    단색 배경 `#fafaf7`, 컴포넌트 없음. 서버가 기동할 때 없는 종류만 넣는다(제목이 `[`·`【` 로 시작하는
                    소재 프레임은 세지 않는다). 관리자가 같은 종류를 더 만들 수 있으니 소재를 뺀 뒤 종류마다 `frameId` 가 가장 작은 것을 쓴다.

                    ⚠️ **내 프레임은 요금제에 따라 잘려 나온다. 지워진 것이 아니다.**
                    보관 기간(BASIC 3일 · PLUS 3개월 · PRO 무제한)을 넘긴 것과
                    보관 한도를 넘는 오래된 것이 목록에서 빠진다. PRO 로 올리면 다시 보인다 —
                    "사라졌다"가 아니라 "지금 요금제로는 안 보인다"로 안내할 것.
                    """)
    @ApiErrors("GEN-031: 토큰은 유효한데 그 계정이 사라짐")
    @GetMapping
    public Response<List<FrameResponse>> getMyFrames(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Response.ok(frameService.getMyFrames(principal.publicId()));
    }

    @Operation(
            summary = "프레임 단건 조회",
            description = """
                    시스템 프레임은 누구나 읽을 수 있고 요금제 검사를 받지 않는다.

                    ⚠️ **남의 프레임은 403 이 아니라 404 다.** 없는 프레임과 응답이 완전히 같다 —
                    id 가 순차 숫자라 403 을 주면 "그 id 가 존재한다"가 새기 때문이다.
                    프론트는 둘을 구분할 수 없고, 구분할 필요도 없다.

                    내 프레임인데도 403 이 날 수 있다. `SUBS-002` 는 보관 기간이 지난 것,
                    `SUBS-003` 은 보관 한도 밖으로 밀려난 것이다. **둘 다 요금제를 올리면 다시 열린다.**
                    """)
    @ApiErrors({
            "SUBS-002: 요금제의 보관 기간이 지난 프레임",
            "SUBS-003: 보관 한도 밖으로 밀려난 프레임 (더 최신 프레임이 한도만큼 있음)",
            "GEN-031: 없는 프레임이거나 남의 프레임 — 구분하지 않는다"
    })
    @GetMapping("/{frameId}")
    public Response<FrameResponse> getFrame(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Parameter(description = "프레임 ID", example = "1") @PathVariable Long frameId) {
        return Response.ok(frameService.getFrame(principal.publicId(), frameId));
    }

    @Operation(
            summary = "프레임 수정",
            description = """
                    **전체 교체다.** 보낸 내용이 그대로 최종 상태가 되므로, 컴포넌트를 하나만 바꾸더라도
                    전체 목록을 다시 보내야 한다. 안 보낸 컴포넌트는 삭제된다.

                    ⚠️ **컴포넌트 ID 가 전부 바뀐다.** 지우고 다시 만들기 때문이다.
                    응답의 새 ID 로 화면 상태를 갱신할 것.

                    조회와 같은 관문을 통과해야 한다 — **목록에서 잘려 안 보이는 프레임은 덮어쓸 수도 없다**
                    (`SUBS-002`/`SUBS-003`). 반면 **보관 한도(개수)는 다시 검사하지 않는다.**
                    이미 있는 프레임을 고치는 것이라 개수가 늘지 않기 때문이다.

                    교체로 쓰이지 않게 된 이전 이미지들은 서버가 S3 에서 지운다. **커밋된 뒤에** 지우므로
                    저장이 실패하면 이전 이미지는 그대로 남는다.
                    """)
    @ApiErrors({
            "SUBS-002: 보관 기간이 지난 프레임",
            "SUBS-003: 보관 한도 밖으로 밀려난 프레임",
            "GEN-006: background.type 이 COLOR·IMAGE 가 아님",
            "GEN-031: 없는 프레임이거나 남의 프레임"
    })
    @PutMapping("/{frameId}")
    public Response<FrameResponse> updateFrame(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @Parameter(description = "프레임 ID", example = "1") @PathVariable Long frameId,
                                               @RequestBody @Valid FrameCreateRequest request) {
        return Response.ok(frameService.updateFrame(principal.publicId(), frameId, request));
    }

    @Operation(
            summary = "프레임 삭제",
            description = """
                    프레임과 컴포넌트, 그리고 딸린 S3 이미지(프리뷰·배경·사진·구운 텍스트)를 함께 지운다.
                    **되돌릴 수 없다.**

                    조회·수정과 달리 **소유권만 본다.** 보관 기간이나 한도 때문에 목록에서 안 보이는 프레임도
                    삭제는 된다 — 안 보인다고 정리조차 못 하면 곤란하기 때문이다.
                    """)
    @ApiErrors("GEN-031: 없는 프레임이거나 남의 프레임 — 시스템 프레임도 여기에 걸린다")
    @DeleteMapping("/{frameId}")
    public Response<Void> deleteFrame(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @Parameter(description = "프레임 ID", example = "1") @PathVariable Long frameId) {
        frameService.deleteFrame(principal.publicId(), frameId);
        return Response.ok();
    }
}
