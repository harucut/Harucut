package com.harucut.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.harucut.entitlement.enums.ContractStatus;
import com.harucut.entitlement.enums.EntitlementScope;
import com.harucut.entitlement.enums.EntitlementSource;
import com.harucut.entitlement.enums.EntitlementStatus;
import com.harucut.entitlement.enums.EvidenceKind;
import com.harucut.entitlement.enums.Feature;
import com.harucut.entitlement.enums.InquiryStatus;
import com.harucut.entitlement.enums.OrganizerType;
import com.harucut.participant.enums.ParticipantChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

// 문의·계약·이용권·사용량 API 의 요청·응답 (ADR-0008 §6)
public final class EntitlementDtos {

    private EntitlementDtos() {
    }

    @Schema(name = "InquiryRequest", description = "행사·기업 문의")
    public record InquiryRequest(
            @NotBlank @Size(max = 50) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contactName,
            @NotBlank @Email @Size(max = 120) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
            @Size(max = 30) String phone,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OrganizerType organizerType,
            @NotEmpty @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<ParticipantChannel> channels,
            LocalDate expectedDate,
            @PositiveOrZero Integer expectedCount,
            @NotBlank @Size(max = 2000) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message
    ) {
    }

    @Schema(name = "InquiryCreated")
    public record InquiryCreated(String inquiryId) {
    }

    @Schema(name = "Inquiry", description = "문의(관리자)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Inquiry(String inquiryId, String contactName, String email, String phone, OrganizerType organizerType,
                          String channels, LocalDate expectedDate, Integer expectedCount, String message,
                          InquiryStatus status, String assigneeNote, OffsetDateTime createdAt) {
    }

    @Schema(name = "InquiryUpdateRequest")
    public record InquiryUpdateRequest(InquiryStatus status, @Size(max = 1000) String assigneeNote) {
    }

    @Schema(name = "EntitlementTerms", description = "이용권 조건 — 계약과 수동 부여가 같은 모양")
    public record Terms(
            @NotNull @Min(0) @Schema(description = "성공 합성 한도(네컷 1장 = 1건)", requiredMode = Schema.RequiredMode.REQUIRED) Integer composeQuota,
            @Min(1) @Schema(description = "결과 보관 일수 상한") Integer retentionDays,
            @Min(0) Integer maxBoothDevices,
            @Min(0) Integer maxLiveEvents,
            Set<Feature> features
    ) {
    }

    @Schema(name = "ContractRequest", description = "계약 — DRAFT 로 만들고 ACTIVE 로 바꾸면 이용권이 생긴다")
    public record ContractRequest(
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workspaceId,
            @NotBlank @Size(max = 100) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contractorName,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EntitlementScope scope,
            @Schema(description = "EVENT 범위일 때 행사 public id 들") List<String> eventIds,
            @PositiveOrZero Long amount,
            @Size(min = 3, max = 3) @Schema(defaultValue = "KRW") String currency,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime startsAt,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime endsAt,
            @Valid Evidence paymentEvidence,
            @NotNull @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Terms entitlement
    ) {
    }

    @Schema(name = "PaymentEvidence", description = "결제·입금 근거 — 참조만 남긴다")
    public record Evidence(@NotNull EvidenceKind kind, @NotBlank @Size(max = 200) String reference) {
    }

    @Schema(name = "Contract")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contract(String contractId, String workspaceId, String contractorName, EntitlementScope scope,
                           List<String> eventIds, Long amount, String currency, OffsetDateTime startsAt,
                           OffsetDateTime endsAt, Evidence paymentEvidence,
                           @Schema(description = "결제 증빙(PORTONE)을 제공자 API 로 대조해 확인한 시각. 없으면 키 없음") OffsetDateTime paymentVerifiedAt,
                           Terms entitlement, ContractStatus status, OffsetDateTime createdAt) {
    }

    @Schema(name = "ContractStatusRequest")
    public record ContractStatusRequest(@NotNull ContractStatus status) {
    }

    @Schema(name = "ManualGrantRequest", description = "수동 이용권(체험·보상·파트너) — 사유 필수. 결제를 꾸미지 않는다")
    public record ManualGrantRequest(
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workspaceId,
            @Schema(description = "있으면 행사 범위") String eventId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime validFrom,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime validTo,
            @NotNull @Valid @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Terms terms,
            @NotBlank @Size(max = 500) @Schema(description = "부여 사유", requiredMode = Schema.RequiredMode.REQUIRED) String grantorNote
    ) {
    }

    @Schema(name = "Entitlement")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entitlement(String entitlementId, EntitlementScope scope, String workspaceId, String eventId,
                              EntitlementSource source, String contractId, Terms terms, int used, int reserved,
                              OffsetDateTime validFrom, OffsetDateTime validTo, EntitlementStatus status,
                              String grantorNote, String revokeReason,
                              @Schema(description = "행사 배분이면 부모(기업 총량) 이용권 public id") String parentEntitlementId,
                              @Schema(description = "부모일 때 활성 자식 배분량 합 — 남은 배분 가능량 = terms.composeQuota − allocatedQuota") int allocatedQuota) {
    }

    @Schema(name = "AllocationRequest", description = "기업 총량에서 행사 하나에 배분 — 기간·기능·조건은 부모를 따른다")
    public record AllocationRequest(
            @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "행사 public id(부모와 같은 공간)") String eventId,
            @NotNull @Min(1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "300") Integer composeQuota) {
    }

    @Schema(name = "AllocationResizeRequest", description = "배분량 바꾸기 — 늘리면 합 검사(ENT-005), 줄이면 사용·예약 아래 금지(ENT-006)")
    public record AllocationResizeRequest(
            @NotNull @Min(1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "400") Integer composeQuota) {
    }

    @Schema(name = "EntitlementRevokeRequest")
    public record RevokeRequest(@NotBlank @Size(max = 500) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) {
    }

    @Schema(name = "EntitlementImpact", description = "회수·만료 영향 미리보기")
    public record Impact(@Schema(description = "이 이용권을 쓰는 운영 중(LIVE) 행사 public id") List<String> liveEvents,
                         @Schema(description = "아직 종결되지 않은 예약 수") long openReservations) {
    }

    @Schema(name = "EventUsage", description = "행사 사용량 — 적용 이용권이 없으면 entitlement 키가 없고 수치는 0")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventUsage(Applied entitlement, int composeQuota, int used, int reserved, int remaining,
                             Integer retentionDays, Integer maxBoothDevices,
                             @Schema(description = "한도 알림 — 확정 사용량이 처음 넘은 비율(80·95·100)과 그 시각. 넘은 것만, 오름차순. 없으면 빈 목록")
                             List<Alert> alerts,
                             @Schema(description = "행사 배분일 때 기업 총량 — 공간 OWNER·ADMIN 에게만. 실제로 찍을 수 있는 양 = min(remaining, parent.remaining)")
                             Parent parent) {

        @Schema(name = "EventUsageParent", description = "기업 총량(부모 이용권)")
        public record Parent(int composeQuota, int used, int reserved, int remaining) {
        }

        @Schema(name = "EventUsageAlert")
        public record Alert(@Schema(description = "넘은 비율(%) — 80·95·100 중 하나(정수)", example = "80") int threshold, OffsetDateTime reachedAt) {
        }

        @Schema(name = "EventUsageEntitlement")
        public record Applied(EntitlementScope scope, OffsetDateTime validFrom, OffsetDateTime validTo, Set<Feature> features) {
        }
    }
}
