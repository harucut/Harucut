package com.harucut.event.controller;

import com.harucut.common.response.Response;
import com.harucut.event.dto.TextStyleResponse;
import com.harucut.text.TextStyle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@Tag(name = "행사 운영")
@RestController
public class TextStyleController {

    @Operation(summary = "자유 문구 스타일 목록",
            description = """
                    참가자 자유 문구(행사 설정 `freeText.styleIds`)에 고를 수 있는 서버 스타일 전체. 로그인 없이 연다.
                    글꼴은 서버에 들어 있는 Pretendard 이고, 미리보기·합성 모두 서버가 같은 렌더로 굽는다.
                    """)
    @GetMapping("/api/public/text-styles")
    public Response<List<TextStyleResponse>> list() {
        return Response.ok(Arrays.stream(TextStyle.values()).map(TextStyleResponse::of).toList());
    }
}
