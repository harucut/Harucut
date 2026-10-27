package com.harucut.compose.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeFailureException;
import com.harucut.media.compose.ComposeLambdaPayload;
import com.harucut.media.compose.FourcutRenderer;
import com.harucut.media.compose.ImageFormat;
import com.harucut.media.compose.RenderResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Lambda 진입점: payload → S3 다운로드 → 렌더 → S3 업로드.
// Lambda의 자동 역직렬화(자체 내장 잭슨)를 안 쓰고 스트림을 직접 파싱한다 —
// 다형성 배경(@JsonTypeInfo)은 서버와 같은 잭슨(tools.jackson)으로 읽어야 어긋나지 않는다.
// 필드들은 워밍된 실행 환경에서 재사용된다 — 호출마다 새로 만들지 않는다
public class ComposeHandler implements RequestStreamHandler {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final S3Client s3Client;
    private final FourcutRenderer renderer = new FourcutRenderer();

    public ComposeHandler() {
        // Lambda 런타임이 부르는 생성자 — region·자격증명은 함수 실행 역할(role)에서 온다
        this(S3Client.create());
    }

    ComposeHandler(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context)
            throws IOException {
        ComposeLambdaPayload payload = MAPPER.readValue(input, ComposeLambdaPayload.class);

        List<byte[]> sources = payload.sourceKeys().stream()
                .map(key -> download(payload.bucket(), key, ComposeFailureCode.SOURCE_MISSING))
                .toList();

        // 자산 목록은 스펙이 소유한다 — 계산이 두 벌이면 서버와 어긋난다
        Map<String, byte[]> assets = new LinkedHashMap<>();
        for (String key : payload.spec().referencedAssetKeys()) {
            assets.put(key, download(payload.bucket(), key, ComposeFailureCode.ASSET_MISSING));
        }

        // outputFormat 없는 payload = 포맷 도입 전 서버 — PNG로 떨어진다.
        // 이 관용이 Lambda 선배포를 안전하게 만든다 (ComposeLambdaPayload 주석 참고)
        ImageFormat format =
                payload.outputFormat() == null ? ImageFormat.PNG : payload.outputFormat();

        RenderResult result = renderer.render(payload.spec(), sources, assets, format);

        // contentType을 문자열로 적지 않는다 — 확장자를 정하는 서버와 값이 갈리면
        // .jpg 파일이 image/png로 올라가고, 그건 S3에 올라간 뒤에야 드러난다
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(payload.bucket()).key(payload.resultKey())
                        .contentType(format.mimeType()).build(),
                RequestBody.fromBytes(result.full()));

        // thumbnailKey 없는 payload = 썸네일 도입 전 서버 — 원본만 올리고 조용히 지나간다.
        // 이 관용 덕에 Lambda를 서버보다 먼저 배포해도 안전하다
        if (payload.thumbnailKey() != null) {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(payload.bucket()).key(payload.thumbnailKey())
                            .contentType("image/jpeg").build(),
                    RequestBody.fromBytes(result.thumbnailJpeg()));
        }

        output.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    // 없는 객체는 종류를 달아 던진다(원본이 만료·삭제됐는지 자산이 사라졌는지) — 그 밖의 S3 오류는 그대로(재시도 대상)
    private byte[] download(String bucket, String key, ComposeFailureCode missing) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ComposeFailureException(missing, "저장소에 없다: " + key, e);
        }
    }
}
