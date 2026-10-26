package com.harucut.event.controller;

import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.event.dto.PublicEventResponse;
import com.harucut.event.service.PublicEventService;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.enums.ParticipantChannel;
import com.harucut.participant.service.ParticipantService;
import com.harucut.common.exception.BusinessException;
import com.harucut.event.exception.EventErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "행사 참가")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public/events/{slug}")
public class PublicEventController {

    private final PublicEventService publicEventService;
    private final ParticipantService participantService;

    @Operation(summary = "행사 참가 정보", description = "로그인 없이 연다. 게시 전·보관 종료 행사는 404.")
    @ApiErrors("GEN-031: 없는 행사이거나 게시 전")
    @GetMapping
    public Response<PublicEventResponse> get(@Parameter(description = "행사 slug", example = "wedding-1027") @PathVariable String slug) {
        return Response.ok(publicEventService.getBySlug(slug));
    }

    @Operation(summary = "개인 휴대폰 참가 세션 발급",
            description = """
                    RESTRICTED 세션을 준다 — 필수 동의 뒤에 ACTIVE 가 되고, 그때부터 회차·업로드·합성을 할 수 있다.
                    토큰은 이후 모든 참가자 API 에 `X-Participant-Token` 헤더로 보낸다(쿠키가 아니다).
                    """)
    @ApiErrors({
            "EVENT-001: 지금 촬영할 수 없는 행사",
            "EVENT-002: 개인 휴대폰 참가를 허용하지 않는 행사",
            "GEN-061: 요청이 너무 잦다",
            "GEN-031: 없는 행사이거나 게시 전"
    })
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Response<ParticipantDtos.SessionResponse> createSession(
            @Parameter(description = "행사 slug", example = "wedding-1027") @PathVariable String slug,
            @RequestBody @Valid ParticipantDtos.SessionRequest request,
            HttpServletRequest servletRequest) {
        if (request.channel() != ParticipantChannel.BYOD) {
            // 부스 참가자는 등록된 기기 자격으로만 만든다(/api/booth/participant-sessions)
            throw new BusinessException(EventErrorCode.CHANNEL_NOT_ALLOWED);
        }
        return Response.ok(participantService.createByodSession(slug, servletRequest.getRemoteAddr()));
    }
}
