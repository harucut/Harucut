package com.harucut.media.entity;

import com.harucut.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개인 사진 공유 링크(/s/&lt;token&gt;) — 사진 하나에 하나다. 받는 사람은 로그인 없이 사진만 보고 저장한다.
 *
 * <p>다른 토큰과 달리 원문을 둔다(ADR-0007 §1 은 해시만) — 주인이 다시 눌러도 같은 주소를 줘야 하고, 이 토큰은 어차피
 * 페이지 경로에 실려 FE 접근 기록·미리보기 수집기·대화방에 남는다(docs/decisions.md 2026-11-16).
 * 열림·닫힘은 저장하지 않는다 — 열 때마다 사진이 주인 기록에 보이는지 본다(MediaShareService).
 * 행은 MediaShareRepository 의 insertIfAbsent 로만 만든다(동시에 두 번 눌러도 유니크 위반 예외가 없다).
 */
@Entity
@Table(name = "media_share", uniqueConstraints = {
        @UniqueConstraint(name = "uk_media_share_media_id", columnNames = "media_id"),
        @UniqueConstraint(name = "uk_media_share_token", columnNames = "token")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaShare extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "media_share_id")
    private Long id;

    // 연관 대신 스칼라 id(result_access 와 같은 선택). DB FK 는 있다(V39)
    @Column(name = "media_id", nullable = false)
    private Long mediaId;

    @Column(name = "token", nullable = false, length = 43, columnDefinition = "char(43)")
    private String token;
}
