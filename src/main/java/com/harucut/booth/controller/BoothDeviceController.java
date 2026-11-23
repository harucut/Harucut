package com.harucut.booth.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.booth.dto.BoothDtos;
import com.harucut.booth.service.BoothDeviceService;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "행사 운영")
@RestController
@RequiredArgsConstructor
public class BoothDeviceController {

    private final BoothDeviceService boothDeviceService;

    @Operation(summary = "부스 기기 등록",
            description = """
                    기기를 만들고 연결 코드(`XXXX-XXXX`, 10분)를 준다. 행사장 기기 화면에서 코드를 입력하면 연결된다.
                    행사당 회수되지 않은 기기는 20대까지.
                    """)
    @ApiErrors({"BOOTH-006: 기기 수 상한", "GEN-031: 없는 행사이거나 OWNER·ADMIN 이 아니다"})
    @PostMapping("/api/auth/events/{eventId}/booth-devices")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<BoothDtos.DeviceResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @Parameter(description = "행사 public id") @PathVariable String eventId,
                                                     @RequestBody @Valid BoothDtos.DeviceCreateRequest request) {
        return Response.ok(boothDeviceService.create(principal.publicId(), eventId, request.name()));
    }

    @Operation(summary = "행사의 부스 기기 목록", description = "연결 코드는 싣지 않는다.")
    @ApiErrors("GEN-031: 없는 행사이거나 OWNER·ADMIN 이 아니다")
    @GetMapping("/api/auth/events/{eventId}/booth-devices")
    public Response<List<BoothDtos.DeviceResponse>> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                         @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(boothDeviceService.list(principal.publicId(), eventId));
    }

    @Operation(summary = "연결 코드 재발급",
            description = "이전 코드는 닫힌다. 이미 연결된 기기의 토큰은 새 코드가 교환되는 순간 무효가 된다(기기 교체).")
    @ApiErrors({"BOOTH-003: 회수된 기기", "GEN-031: 없는 기기이거나 OWNER·ADMIN 이 아니다"})
    @PostMapping("/api/auth/booth-devices/{deviceId}/pairing-codes")
    public Response<BoothDtos.DeviceResponse> reissue(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "기기 public id") @PathVariable String deviceId) {
        return Response.ok(boothDeviceService.reissueCode(principal.publicId(), deviceId));
    }

    @Operation(summary = "부스 기기 회수",
            description = "기기 토큰을 무효로 하고 열린 코드를 닫는다. 진행 중 회차를 종료하고 그 기기로 만든 참가자 세션을 회수한다. 되돌릴 수 없다.")
    @ApiErrors("GEN-031: 없는 기기이거나 OWNER·ADMIN 이 아니다")
    @PostMapping("/api/auth/booth-devices/{deviceId}/revoke")
    public Response<BoothDtos.DeviceResponse> revoke(@AuthenticationPrincipal AuthenticatedUser principal,
                                                     @Parameter(description = "기기 public id") @PathVariable String deviceId) {
        return Response.ok(boothDeviceService.revoke(principal.publicId(), deviceId));
    }
}
