package com.harucut.media.compose;

import java.util.List;

// 서버 → Lambda로 넘어가는 작업 한 건의 계약.
// 서버(LambdaComposeExecutor)가 만들고 Lambda(ComposeHandler)가 읽는다 —
// 같은 모듈에 있어서 계약이 어긋나면 런타임이 아니라 컴파일에서 깨진다.
// 버킷도 여기 실린다: 설정의 원천을 서버 한 곳으로 유지한다 (Lambda 환경변수 없음).
// thumbnailKey는 null일 수 있다 — 썸네일 도입 전 서버가 보낸 payload와의 호환
// jobId는 Lambda가 읽지 않는다. Destination 통지에 원본 payload가 그대로 실려 오므로, 결과를 어느 Job에 적을지 알아내는 데 서버가 쓴다.
// outputFormat도 null일 수 있다 — 포맷 도입 전 서버와의 호환. 규칙은 thumbnailKey와 같다 (아래)
public record ComposeLambdaPayload(
        String bucket,
        Long jobId,
        ComposeSpec spec,
        List<String> sourceKeys,
        String resultKey,
        String thumbnailKey,

        // 결과물 저장 포맷. **서버가 지시하고 Lambda는 따른다.**
        // 렌더러나 Lambda가 자체 기본값을 갖지 않는 이유: 서버가 만드는 resultKey의 확장자와
        // 실제 바이트가 반드시 같아야 하는데, 그 둘을 정하는 주체가 갈리면 어긋날 수 있다.
        // 서버 한 곳에서 키와 포맷을 함께 계산하면 어긋날 창 자체가 없다.
        //
        // null = 포맷 필드 도입 전 서버가 보낸 payload → 옛 동작(PNG)으로 떨어진다.
        // 이 관용 덕에 Lambda를 서버보다 먼저 배포해도 안전하다 — 반대로 서버가 먼저 나가면
        // .jpg 키에 PNG 바이트가 들어간다. 썸네일 도입 때 쓴 것과 같은 패턴이다
        ImageFormat outputFormat,

        // 앱이 이 접수에 매긴 시도 번호(claim 마다 +1). AWS 내부 재시도는 같은 번호다. 실패 통지는 지금 번호와 같을 때만 반영된다(R5 D09)
        Integer attemptNo,

        // 출력 계약 — null·1 = 예전(무조건 PUT, 체크섬 없음), 2 = 두 출력을 조건부 생성하고 SHA-256 쌍을 검증해 응답에 싣는다.
        // 작업마다 접수 때 고정된다(재시도가 계약을 바꾸지 않는다). ComposeOutputContract 참고
        Integer contract
) {

    // 시도 번호·계약 도입 전 호출부 — 예전 계약으로 간다
    public ComposeLambdaPayload(String bucket, Long jobId, ComposeSpec spec, List<String> sourceKeys, String resultKey,
                                String thumbnailKey, ImageFormat outputFormat) {
        this(bucket, jobId, spec, sourceKeys, resultKey, thumbnailKey, outputFormat, null, null);
    }

    public boolean verifiedPair() {
        return contract != null && contract >= ComposeOutputContract.VERIFIED_PAIR;
    }
}
