package com.harucut.entitlement.controller;

import com.harucut.auth.security.AuthenticatedUser;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.common.response.Response;
import com.harucut.config.openapi.ApiErrors;
import com.harucut.entitlement.dto.EntitlementDtos;
import com.harucut.entitlement.service.EntitlementService;
import com.harucut.event.entity.Event;
import com.harucut.event.repository.EventRepository;
import com.harucut.event.service.EventAccessPolicy;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "행사 운영")
@RestController
@RequiredArgsConstructor
public class EventUsageController {

    private final EntitlementService entitlementService;
    private final EventAccessPolicy policy;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    @Operation(summary = "행사 사용량",
            description = """
                    운영자(OWNER·ADMIN) 또는 그 행사 사진 권한이 있는 사람. 적용 이용권의 합성 한도·사용·예약·남은 수와 기간·기능.
                    적용 이용권이 없으면 entitlement 키가 없고 수치는 0. 계약 금액은 싣지 않는다.
                    """)
    @ApiErrors("GEN-031: 없는 행사이거나 볼 수 없다")
    @GetMapping("/api/auth/events/{eventId}/usage")
    public Response<EntitlementDtos.EventUsage> usage(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Parameter(description = "행사 public id") @PathVariable String eventId) {
        Long userId = userRepository.findByPublicId(principal.publicId()).map(User::getId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        Event event = eventRepository.findByPublicId(eventId)
                .filter(found -> policy.canSee(found, userId))
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
        return Response.ok(entitlementService.usage(event));
    }
}
