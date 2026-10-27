package com.harucut.media.compose;

import java.util.List;

// 합성 요청 커밋 후 실행 스레드로 넘어가는 payload — 실행에 필요한 전부를 담아
// 워커가 Job을 다시 읽지 않고 바로 실행할 수 있다 (DB 기록 시점에만 Job을 읽는다)
public record ComposeRequestedEvent(
        Long jobId,
        ComposeSpec spec,
        List<String> sourceKeys,
        String resultKey,
        String thumbnailKey,

        // resultKey와 **같이** 실려 다닌다. 둘을 떼어 놓으면 키는 .jpg인데 내용은 PNG인
        // 상태가 만들어지고, 그건 컴파일에도 테스트에도 안 잡힌다.
        // 한 곳(ComposeService.RESULT_FORMAT)에서 둘 다 파생시켜 어긋날 창을 없앤다
        ImageFormat outputFormat,

        // 접수 때 고정한 출력 계약(ComposeOutputContract) — null 이면 예전 계약
        Integer contract,

        // 이번 접수의 시도 번호 — 워커가 claim 을 이긴 뒤에 채운다(withAttempt). 이벤트를 만들 때는 null
        Integer attemptNo
) {

    public ComposeRequestedEvent(Long jobId, ComposeSpec spec, List<String> sourceKeys, String resultKey,
                                 String thumbnailKey, ImageFormat outputFormat) {
        this(jobId, spec, sourceKeys, resultKey, thumbnailKey, outputFormat, null, null);
    }

    public ComposeRequestedEvent withAttempt(int attemptNo) {
        return new ComposeRequestedEvent(jobId, spec, sourceKeys, resultKey, thumbnailKey, outputFormat, contract, attemptNo);
    }
}
