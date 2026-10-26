package com.harucut.draft.service;

import com.harucut.asset.entity.Asset;
import com.harucut.common.exception.BusinessException;
import com.harucut.common.exception.GlobalErrorCode;
import com.harucut.draft.config.FrameDraftProperties;
import com.harucut.draft.dto.DraftCreateRequest;
import com.harucut.draft.dto.DraftResponse;
import com.harucut.draft.dto.DraftUpdateRequest;
import com.harucut.draft.entity.FrameDraft;
import com.harucut.draft.entity.FrameDraftAsset;
import com.harucut.draft.exception.DraftErrorCode;
import com.harucut.draft.repository.FrameDraftAssetRepository;
import com.harucut.draft.repository.FrameDraftRepository;
import com.harucut.frame.service.FrameAssetResolver;
import com.harucut.frame.service.FrameService;
import com.harucut.user.entity.User;
import com.harucut.user.repository.UserRepository;
import com.harucut.workspace.entity.Workspace;
import com.harucut.workspace.enums.WorkspaceRole;
import com.harucut.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 편집기 문서의 서버 초안 (ADR-0006 §5). 로그인한 제작자만 쓴다 — 일반 게스트는 이 경로가 없다.
 * 두 기기 편집은 revision 조건부 UPDATE 로 막고, 자산 참조는 요청의 assetIds 로 기록한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class FrameDraftService {

    private static final Set<WorkspaceRole> EDIT_ROLES =
            Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.DESIGNER);
    private static final String NEW_FRAME = "new";

    private final FrameDraftRepository frameDraftRepository;
    private final FrameDraftAssetRepository frameDraftAssetRepository;
    private final FrameAssetResolver frameAssetResolver;
    private final FrameService frameService;
    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final FrameDraftProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DraftResponse create(String publicId, DraftCreateRequest request) {
        User user = getUser(publicId);
        String document = serialize(request.document());
        if (request.frameId() != null) {
            frameService.getOwnedReadableFrame(user, request.frameId());
        }
        Workspace workspace = request.workspaceId() == null
                ? workspaceService.personalWorkspaceOf(user.getId())
                : workspaceService.requireRole(request.workspaceId(), user.getId(), EDIT_ROLES);
        List<Asset> assets = resolveAssets(request.assetIds(), user.getId());

        FrameDraft draft = frameDraftRepository.save(
                FrameDraft.create(workspace.getId(), user.getId(), request.frameId(), document));
        assets.forEach(asset -> frameDraftAssetRepository.save(new FrameDraftAsset(draft.getId(), asset.getId())));
        return DraftResponse.full(draft, request.document());
    }

    @Transactional(readOnly = true)
    public List<DraftResponse> list(String publicId, String frameIdFilter) {
        User user = getUser(publicId);
        List<FrameDraft> drafts;
        if (frameIdFilter == null) {
            drafts = frameDraftRepository.findAllByUserIdOrderByUpdatedAtDesc(user.getId());
        } else if (NEW_FRAME.equals(frameIdFilter)) {
            drafts = frameDraftRepository.findAllByUserIdAndFrameIdIsNullOrderByUpdatedAtDesc(user.getId());
        } else {
            drafts = frameDraftRepository.findAllByUserIdAndFrameIdOrderByUpdatedAtDesc(user.getId(), parseFrameId(frameIdFilter));
        }
        return drafts.stream().map(DraftResponse::summary).toList();
    }

    @Transactional(readOnly = true)
    public DraftResponse get(String publicId, String draftId) {
        FrameDraft draft = find(getUser(publicId), draftId);
        return DraftResponse.full(draft, objectMapper.readTree(draft.getDocument()));
    }

    public DraftResponse update(String publicId, String draftId, DraftUpdateRequest request) {
        User user = getUser(publicId);
        FrameDraft draft = find(user, draftId);
        String document = serialize(request.document());
        List<Asset> assets = resolveAssets(request.assetIds(), user.getId());

        if (frameDraftRepository.updateIfRevision(draft.getId(), request.revision(), document,
                LocalDateTime.now(clock)) == 0) {
            FrameDraft current = frameDraftRepository.findById(draft.getId()).orElseThrow();
            throw new BusinessException(DraftErrorCode.REVISION_CONFLICT,
                    "초안 revision 불일치: 요청 " + request.revision() + " / 서버 " + current.getRevision(),
                    Map.of("revision", current.getRevision(), "updatedAt", current.getUpdatedAt()));
        }
        frameDraftAssetRepository.deleteByFrameDraftIdIn(List.of(draft.getId()));
        assets.forEach(asset -> frameDraftAssetRepository.save(new FrameDraftAsset(draft.getId(), asset.getId())));

        FrameDraft saved = frameDraftRepository.findById(draft.getId()).orElseThrow();
        return DraftResponse.full(saved, request.document());
    }

    public void delete(String publicId, String draftId) {
        FrameDraft draft = find(getUser(publicId), draftId);
        frameDraftAssetRepository.deleteByFrameDraftIdIn(List.of(draft.getId()));
        frameDraftRepository.deleteByIdIn(List.of(draft.getId()));
    }

    private String serialize(JsonNode document) {
        String json = objectMapper.writeValueAsString(document);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxBytes()) {
            throw new BusinessException(DraftErrorCode.DOCUMENT_TOO_LARGE,
                    "초안 " + bytes + "바이트 > 상한 " + properties.maxBytes());
        }
        return json;
    }

    private List<Asset> resolveAssets(List<String> assetIds, Long userId) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        return assetIds.stream().distinct()
                .map(assetId -> frameAssetResolver.requireForDraft(assetId, userId))
                .toList();
    }

    // 내 초안만 — 남의 것과 없는 것은 같은 404
    private FrameDraft find(User user, String draftId) {
        return frameDraftRepository.findByPublicIdAndUserId(draftId, user.getId())
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    private static Long parseFrameId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(GlobalErrorCode.TYPE_MISMATCH, "frameId 는 숫자이거나 new 여야 한다: " + value);
        }
    }

    private User getUser(String publicId) {
        return userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(GlobalErrorCode.NOT_FOUND));
    }
}
