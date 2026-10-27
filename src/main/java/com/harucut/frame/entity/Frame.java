package com.harucut.frame.entity;

import com.harucut.common.entity.BaseEntity;
import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.cell.PhotoCell;
import com.harucut.frame.converter.BackgroundConverter;
import com.harucut.frame.converter.CellCutoutsConverter;
import com.harucut.frame.converter.PhotoCellsConverter;
import com.harucut.frame.enums.FrameType;
import com.harucut.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "frame")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Frame extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "frame_id")
    private Long id;

    @Column(nullable = false)
    private String title;

    // 발행 번호 카운터 — 발행 때 SQL 로만 올린다(FrameRepository.bumpVersionNo). 엔티티 저장이 예전 값으로 덮지 않게 읽기 전용
    @ColumnDefault("0")
    @Column(name = "last_version_no", nullable = false, insertable = false, updatable = false)
    private int lastVersionNo;

    @Column(nullable = false)
    private String description;

    @Column(name = "preview_key", nullable = false, length = 1024)
    private String previewKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "frame_type", nullable = false, length = 32)
    private FrameType frameType;

    @Convert(converter = BackgroundConverter.class)
    @Column(nullable = false, length = 4000)
    private BackgroundAttributes background;

    // 셀 누끼 토글 — 항상 정확히 4개, 촬영 슬롯 순서.
    // 서버는 이 값으로 그리지 않는다(누끼는 프론트가 원본에 구워 올린다) —
    // 편집기가 저장 프레임을 다시 열 때 토글을 복원하도록 응답에 나가는 게 남은 역할이다
    @Convert(converter = CellCutoutsConverter.class)
    @Column(name = "cell_cutouts", length = 64)
    private List<Boolean> cellCutouts;

    // 자유 사진 칸 4개(ADR-0012) — null 이면 frameType 의 고정 레이아웃. 검증은 PhotoCells 가 저장 전에 한다
    @Convert(converter = PhotoCellsConverter.class)
    @Column(name = "photo_cells", length = 2000)
    private List<PhotoCell> photoCells;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    // 속한 공간. 사용자 프레임은 그 사용자의 개인 공간, 시스템 프레임은 NULL(SYSTEM 범위).
    // P1 의 소유 판정은 아직 user 로 한다 — 이 값은 자산·발행본이 같은 공간인지 볼 때 쓴다.
    // V2 이전에 만든 행은 NULL 일 수 있다(flyway 꺼진 운영은 백필이 안 돈다)
    @Column(name = "workspace_id")
    private Long workspaceId;

    // 낙관적 잠금. PUT 이 version 을 보내면 이 값과 비교하고(FRAME-002), 보내지 않아도 동시 저장끼리는
    // flush 시점에 JPA 가 막는다. 응답에 실려 다음 PUT 에 그대로 돌아온다
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "frame", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FrameComponent> components = new ArrayList<>();

    private Frame(String title, String description, String previewKey, FrameType frameType,
                  BackgroundAttributes background, List<Boolean> cellCutouts,
                  User user, boolean isSystem) {
        this.title = title;
        this.description = description;
        this.previewKey = previewKey;
        this.frameType = frameType;
        this.background = background;
        this.cellCutouts = normalizeCellCutouts(cellCutouts);
        this.user = user;
        this.isSystem = isSystem;
    }

    public void assignWorkspace(Long workspaceId) {
        if (isSystem) {
            throw new IllegalStateException("시스템 프레임은 공간에 속하지 않는다");
        }
        this.workspaceId = workspaceId;
    }

    public static Frame owned(User user, String title, String description, String previewKey,
                              FrameType frameType, BackgroundAttributes background) {
        return owned(user, title, description, previewKey, frameType, background, null);
    }

    public static Frame owned(User user, String title, String description, String previewKey,
                              FrameType frameType, BackgroundAttributes background,
                              List<Boolean> cellCutouts) {
        if (user == null) {
            throw new IllegalArgumentException("사용자 프레임에는 소유자가 필요하다");
        }
        return new Frame(title, description, previewKey, frameType, background, cellCutouts,
                user, false);
    }

    public static Frame system(String title, String description, String previewKey,
                               FrameType frameType, BackgroundAttributes background) {
        return system(title, description, previewKey, frameType, background, null);
    }

    public static Frame system(String title, String description, String previewKey,
                               FrameType frameType, BackgroundAttributes background,
                               List<Boolean> cellCutouts) {
        return new Frame(title, description, previewKey, frameType, background, cellCutouts,
                null, true);
    }

    public void updateMetadata(String title, String description, BackgroundAttributes background,
                               String previewKey, List<Boolean> cellCutouts) {
        this.title = title;
        this.description = description;
        this.background = background;
        this.previewKey = previewKey;
        this.cellCutouts = normalizeCellCutouts(cellCutouts);
    }

    public void assignPhotoCells(List<PhotoCell> photoCells) {
        this.photoCells = photoCells == null ? null : List.copyOf(photoCells);
    }

    public boolean hasFreeCells() {
        return photoCells != null;
    }

    // cellCutouts 불변식의 소유자 — 어떤 입력이 와도 "정확히 4개, null 없음"으로 만든다.
    // 쓰기(생성·수정)와 읽기(CellCutoutsConverter)가 같은 규칙을 공유한다
    public static List<Boolean> normalizeCellCutouts(List<Boolean> raw) {
        List<Boolean> result = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            result.add(raw != null && i < raw.size() && Boolean.TRUE.equals(raw.get(i)));
        }
        return List.copyOf(result);
    }

    public void addComponent(FrameComponent component) {
        components.add(component);
        component.assignFrame(this);
    }

    // 전체 교체 수정의 앞단 — orphanRemoval이 지워진 컴포넌트를 DB에서 삭제한다
    public void clearComponents() {
        components.clear();
    }

    // addComponent가 유일한 추가 경로여야 하므로 원본 리스트를 내주지 않는다
    public List<FrameComponent> getComponents() {
        return Collections.unmodifiableList(components);
    }
}
