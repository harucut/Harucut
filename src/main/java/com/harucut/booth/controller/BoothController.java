package com.harucut.booth.controller;

import com.harucut.booth.dto.BoothDtos;
import com.harucut.booth.security.BoothPrincipal;
import com.harucut.booth.service.BoothService;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.result.dto.ResultTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "부스")
@RestController
@RequiredArgsConstructor
public class BoothController {

    private final BoothService boothService;

    @Operation(summary = "연결 코드 교환",
            description = """
                    기기 화면에서 운영자가 받은 코드를 입력한다. 성공은 한 번만 — 토큰 원문은 이 응답에서만 볼 수 있다.
                    토큰은 이후 `X-Booth-Token` 헤더로 보낸다. 실패 5회면 그 코드가 닫힌다(운영자가 재발급).
                    """)
    @ApiErrors({"BOOTH-001: 틀렸거나 만료된 코드", "BOOTH-002: 실패 한도로 닫힌 코드", "GEN-061: 요청이 너무 잦다"})
    @PostMapping("/api/public/booth-pairings/redeem")
    public Response<BoothDtos.RedeemResponse> redeem(@RequestBody @Valid BoothDtos.RedeemRequest request,
                                                     HttpServletRequest servletRequest) {
        return Response.ok(boothService.redeem(request.code(), servletRequest.getRemoteAddr()));
    }

    @Operation(summary = "기기 상태와 배정 행사", description = "event 는 공개 행사 정보와 같은 모양. 게시 전·보관 종료면 event 키가 없다.")
    @GetMapping("/api/booth/me")
    public Response<BoothDtos.MeResponse> me(@AuthenticationPrincipal BoothPrincipal principal) {
        return Response.ok(boothService.me(principal));
    }

    @Operation(summary = "다음 손님 세션",
            description = """
                    새 참가자(BOOTH)와 RESTRICTED 세션(30분). 이후 동의·회차·업로드·합성은 이 participantToken 으로 `/api/participant/**` 를 쓴다.
                    부스 세션의 회차는 기기당 활성 1개(BOOTH-004). 결과 목록·다운로드는 열리지 않는다(PART-006).
                    """)
    @ApiErrors({"EVENT-002: 부스 참가를 허용하지 않는 행사", "EVENT-001: 지금 촬영할 수 없다"})
    @PostMapping("/api/booth/participant-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ParticipantDtos.SessionResponse> nextParticipant(@AuthenticationPrincipal BoothPrincipal principal) {
        return Response.ok(boothService.nextParticipant(principal));
    }

    @Operation(summary = "지금 회차",
            description = """
                    활성 회차가 없으면 200 이고 `data` 키가 없다.
                    `version` 이 서버 stateVersion 과 다르면 `job` 을 싣지 않는다 — 늦게 온 이전 손님 결과를 새 손님 화면에 보이지 않게.
                    결과 토큰은 싣지 않는다(합성 접수 응답의 resultToken 을 쓴다).
                    """)
    @GetMapping("/api/booth/capture-runs/current")
    public Response<BoothDtos.RunView> current(@AuthenticationPrincipal BoothPrincipal principal,
                                               @Parameter(description = "화면이 알고 있는 stateVersion") @RequestParam(required = false) Integer version) {
        return Response.ok(boothService.current(principal, version));
    }

    @Operation(summary = "회차 닫기", description = "회차를 끝내고 그 손님 세션을 회수한다. 다음 손님은 세션 발급부터.")
    @ApiErrors({"BOOTH-005: stateVersion 불일치", "GEN-031: 이 기기의 회차가 아니다"})
    @PostMapping("/api/booth/capture-runs/{captureRunId}/close")
    public Response<BoothDtos.RunView> close(@AuthenticationPrincipal BoothPrincipal principal,
                                             @Parameter(description = "회차 public id") @PathVariable String captureRunId,
                                             @RequestBody @Valid BoothDtos.RunStateRequest request) {
        return Response.ok(boothService.close(principal, captureRunId, request.stateVersion()));
    }

    @Operation(summary = "회차 결과 토큰 다시 발급",
            description = "명시적으로 부를 때만 새 토큰을 만든다(이전 토큰도 유효). 정상 경로는 합성 접수 응답의 resultToken 이다.")
    @ApiErrors({"BOOTH-005: stateVersion 불일치", "RESULT-003: 이 회차에 합성 작업이 없다", "GEN-031: 이 기기의 회차가 아니다"})
    @PostMapping("/api/booth/capture-runs/{captureRunId}/result-access")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ResultTokenResponse> resultAccess(@AuthenticationPrincipal BoothPrincipal principal,
                                                      @Parameter(description = "회차 public id") @PathVariable String captureRunId,
                                                      @RequestBody @Valid BoothDtos.RunStateRequest request) {
        return Response.ok(boothService.resultAccess(principal, captureRunId, request.stateVersion()));
    }
}
