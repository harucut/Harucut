package com.harucut.frame.service;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.entity.Frame;
import com.harucut.frame.enums.FrameType;
import com.harucut.frame.repository.FrameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 기본 시스템 프레임 — frameType 마다 하나. 회원이 기본 레이아웃으로 찍으면 FE 가 프레임 목록에서 이것을 frameType 으로 찾아
 * 서버 합성을 부른다. 없으면 기기에서만 합성돼 기록에 남지 않는다(2026-10-18 운영 QA 에서 목록이 비어 있었다).
 * 관리자 API 없이 어느 환경에서든 있도록 기동할 때 없는 종류만 넣고, 이미 있는 것은 건드리지 않는다.
 * migration 이 아닌 까닭: 로컬(ddl-auto update)과 시험(H2 create-drop)은 Flyway 를 돌리지 않는다.
 * 앱이 한 대라(deploy/prod/compose.yml) 두 번 넣을 일이 없다 — 두 대가 동시에 처음 뜨면 같은 종류가 둘 생길 수 있고,
 * 그때 FE 는 가장 먼저 등록된 것을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemFrameSeeder implements ApplicationRunner {

    // 단색이어야 한다 — 회원 합성은 고른 색을 backgroundColor 로 덮어 보내는데, 이미지 배경 프레임에는 400 이다.
    // 값은 FE 기본 바탕색(DEFAULT_FRAME_BACKGROUND_COLOR)이라 색을 보내지 않은 합성도 FE 미리보기와 같다
    static final BackgroundAttributes BACKGROUND = new BackgroundAttributes.Color("#fafaf7");

    private final FrameRepository frameRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Set<FrameType> missing = EnumSet.allOf(FrameType.class);
        frameRepository.findAllWithComponentsBySystem().stream()
                .filter(frame -> !isMaterial(frame.getTitle()))
                .forEach(frame -> missing.remove(frame.getFrameType()));
        for (FrameType type : missing) {
            frameRepository.save(Frame.system(title(type), "", previewKey(type), type, BACKGROUND));
            log.info("기본 시스템 프레임을 넣었다: {}", type);
        }
    }

    // 이름은 FE 기본 카탈로그(harucut_FE apps/web/constants/frames.ts)와 같다. 종류가 늘면 여기서 컴파일이 깨진다
    static String title(FrameType type) {
        return switch (type) {
            case CLASSIC -> "세로 4컷";
            case WIDE -> "가로 4컷";
            case GRID -> "네모 4컷";
            case POLAROID -> "즉석사진 4컷";
        };
    }

    // 미리보기 이미지는 없다 — FE 는 기본 프레임을 frameType 레이아웃으로 직접 그린다. 그래도 preview_key 는 NOT NULL 이고
    // 관리자 API 도 비우지 못하게 하므로 uploads/ 밖의 이름을 둔다(관리 대상이 아니라 응답 presign·삭제에서 빠진다)
    static String previewKey(FrameType type) {
        return "system/frames/" + type.name().toLowerCase(Locale.ROOT) + ".png";
    }

    // FE 는 제목이 [배경]·[스티커] 로 시작하는 시스템 프레임을 편집기 소재로 쓰고 합성 기준에서 뺀다(harucut_FE docs/frame-assets.md).
    // 소재도 frameType 을 가지므로 세면 그 종류의 기본 프레임이 안 들어간다. 여기서는 괄호로 시작하는 제목을 모두 소재로 본다 —
    // FE 가 태그를 늘려도 기본 프레임이 빠지지 않고, 소재가 아닌 것을 소재로 잘못 봐도 같은 종류가 하나 더 생길 뿐이다
    static boolean isMaterial(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).strip();
        return normalized.startsWith("[") || normalized.startsWith("【");
    }
}
