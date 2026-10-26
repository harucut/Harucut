package com.harucut.event.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.event.dto.EffectiveSettingsResponse;
import com.harucut.event.dto.EventPatchRequest;
import com.harucut.event.dto.EventResponse;
import com.harucut.event.dto.EventSettings;
import com.harucut.event.service.EventService;
import com.harucut.event.dto.PhotoDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "행사 운영")
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "행사 만들기(초안)",
            description = "조직 공간의 OWNER·ADMIN 만. `name`·`slug` 가 필수이고 나머지는 기본값(BYOD 허용·부스 꺼짐·보관 30일)이다.")
    @ApiErrors({
            "GEN-002: 개인 공간이거나 name·slug·시간대·보관 일수·촬영 기간이 잘못됐다",
            "EVENT-004: slug 중복",
            "GEN-031: 공간이 없거나 권한이 없다"
    })
    @PostMapping("/api/auth/workspaces/{workspaceId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EventResponse> create(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @Parameter(description = "조직 공간 public id") @PathVariable String workspaceId,
                                          @RequestBody @Valid EventSettings settings) {
        return Response.ok(eventService.create(principal.publicId(), workspaceId, settings));
    }

    @Operation(summary = "공간의 행사 목록", description = "최근에 만든 순.")
    @ApiErrors("GEN-031: 공간이 없거나 권한이 없다")
    @GetMapping("/api/auth/workspaces/{workspaceId}/events")
    public Response<List<EventResponse>> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Parameter(description = "조직 공간 public id") @PathVariable String workspaceId) {
        return Response.ok(eventService.list(principal.publicId(), workspaceId));
    }

    @Operation(summary = "행사 상세(설정 초안 포함)")
    @ApiErrors("GEN-031: 없는 행사이거나 권한이 없다")
    @GetMapping("/api/auth/events/{eventId}")
    public Response<EventResponse> get(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.get(principal.publicId(), eventId));
    }

    @Operation(summary = "유효 설정 미리보기",
            description = """
                    게시하면 발행본에 들어갈 값 = 공간 기본값 ⊕ 프로젝트 기본값 ⊕ 행사 직접 값(경로 단위로 뒤가 이김).
                    `sources` 는 값이 있는 경로마다 출처(WORKSPACE·PROJECT·EVENT). 테마 하위 키(theme.colors.bg 등)까지 준다.
                    Studio 는 이것으로 「상속값/직접 변경값」 표시와 「상속으로 되돌리기(그 필드에 null PATCH)」를 만든다.
                    """)
    @ApiErrors("GEN-031: 없는 행사이거나 권한이 없다")
    @GetMapping("/api/auth/events/{eventId}/effective-settings")
    public Response<EffectiveSettingsResponse> effectiveSettings(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                 @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.effectiveSettings(principal.publicId(), eventId));
    }

    @Operation(summary = "설정 초안 수정",
            description = """
                    `settings` 에 **보낸 필드만** 바뀐다. null 을 보내면 그 값을 비운다(촬영 기간 등).
                    상속 가능한 필드(시간대·참가 채널·보관 일수·레이아웃·프레임·동의 항목·테마)는 null 이면 행사의 직접 값을 지우고
                    공간·프로젝트 기본값을 따른다. 테마는 객체째 바꾸고, 그 안에서 빠진 키는 위 단계 값을, 문구·색 "" 는 FE 기본값을,
                    로고·배경 "" 는 이미지 없음을 뜻한다(글꼴 "" 는 400). `projectId` 로 프로젝트를 지정·해제한다(게시 뒤 409 PROJECT-002,
                    보관된 프로젝트 409 PROJECT-001).
                    `settingsVersion` 이 서버와 다르면 409 `EVENT-005` + `data.currentSettingsVersion`.
                    게시한 행사의 slug 는 바꿀 수 없다.
                    """)
    @ApiErrors({
            "EVENT-005: settingsVersion 불일치",
            "EVENT-004: slug 중복",
            "GEN-002: 값이 잘못됐거나 게시 후 slug 변경·다른 공간의 프로젝트",
            "PROJECT-001: 보관된 프로젝트",
            "PROJECT-002: 게시한 행사의 프로젝트 변경",
            "GEN-031: 없는 행사이거나 권한이 없다"
    })
    @PatchMapping("/api/auth/events/{eventId}")
    public Response<EventResponse> patch(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @Parameter(description = "행사 public id") @PathVariable String eventId,
                                         @RequestBody @Valid EventPatchRequest request) {
        return Response.ok(eventService.patch(principal.publicId(), eventId, request));
    }

    @Operation(summary = "경험 게시",
            description = """
                    설정 초안을 검증해 새 경험 발행본을 만들고 현재 포인터를 옮긴다. 처음이면 상태가 LIVE 가 된다.
                    검증 실패는 400 `EVENT-003` 이고 `data.violations` 에 `[{field, reason}]` 이 모두 실린다.
                    **빈 프레임 목록을 기본 카탈로그로 대체하지 않는다.** 이미 시작한 촬영 회차는 받았던 버전으로 끝난다.
                    """)
    @ApiErrors({
            "EVENT-003: 게시 검증 실패",
            "GEN-031: 없는 행사이거나 권한이 없다"
    })
    @PostMapping("/api/auth/events/{eventId}/publish")
    public Response<EventResponse> publish(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.publish(principal.publicId(), eventId));
    }

    @Operation(summary = "일시 중지", description = "새 세션·회차·합성을 막는다. 이미 접수한 작업과 결과 수령은 계속된다.")
    @ApiErrors({"EVENT-001: 운영 중(LIVE)이 아니다", "GEN-031: 없는 행사이거나 권한이 없다"})
    @PostMapping("/api/auth/events/{eventId}/pause")
    public Response<EventResponse> pause(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.pause(principal.publicId(), eventId));
    }

    @Operation(summary = "재개")
    @ApiErrors({"EVENT-001: 운영 중(LIVE)이 아니다", "GEN-031: 없는 행사이거나 권한이 없다"})
    @PostMapping("/api/auth/events/{eventId}/resume")
    public Response<EventResponse> resume(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.resume(principal.publicId(), eventId));
    }

    @Operation(summary = "촬영 종료", description = "상태가 CAPTURE_CLOSED 가 된다. 결과 수령은 보관 기간 동안 계속된다.")
    @ApiErrors({"EVENT-001: 운영 중(LIVE)이 아니다", "GEN-031: 없는 행사이거나 권한이 없다"})
    @PostMapping("/api/auth/events/{eventId}/close-capture")
    public Response<EventResponse> closeCapture(@AuthenticationPrincipal AuthenticatedUser principal,
                                                @Parameter(description = "행사 public id") @PathVariable String eventId) {
        return Response.ok(eventService.closeCapture(principal.publicId(), eventId));
    }

    @Operation(summary = "행사 복제",
            description = """
                    설정 초안(허용 레이아웃·제공 프레임·동의 항목·테마)과 참가 방식·보관 일수만 새 DRAFT 행사로. 일정은 복제하지 않는다.
                    사진·참가자·결과 토큰·고객 초대·사진 권한·동의 기록·부스는 복제하지 않는다. 복제한 사람에게 사진 권한 네 가지를 준다.
                    """)
    @ApiErrors({"EVENT-004: slug 중복", "GEN-002: slug 형식", "GEN-031: 없는 행사이거나 권한이 없다"})
    @PostMapping("/api/auth/events/{eventId}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<EventResponse> cloneEvent(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Parameter(description = "행사 public id") @PathVariable String eventId,
                                              @RequestBody @Valid PhotoDtos.CloneRequest request) {
        return Response.ok(eventService.cloneEvent(principal.publicId(), eventId, request.name(), request.slug()));
    }
}
