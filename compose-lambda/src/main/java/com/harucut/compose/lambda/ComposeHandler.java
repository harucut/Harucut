package com.harucut.compose.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeFailureException;
import com.harucut.media.compose.ComposeLambdaPayload;
import com.harucut.media.compose.ComposeLambdaResult;
import com.harucut.media.compose.FourcutRenderer;
import com.harucut.media.compose.ImageFormat;
import com.harucut.media.compose.RenderResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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

        if (payload.verifiedPair()) {
            MAPPER.writeValue(output, writeVerifiedPair(payload, format, result));
            return;
        }

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

    /**
     * 출력 계약 2(R5 D09) — 두 출력을 조건부 생성하고 실제 저장된 한 쌍이 이 실행의 바이트와 같을 때만 성공을 돌려준다.
     * 덮어쓰지도, 지우고 다시 쓰지도 않는다. 다른 바이트가 이미 있으면 OUTPUT_CONFLICT 로 실패한다(공개하지 않는다)
     */
    private ComposeLambdaResult writeVerifiedPair(ComposeLambdaPayload payload, ImageFormat format, RenderResult result) {
        if (payload.thumbnailKey() == null) {
            throw new ComposeFailureException(ComposeFailureCode.OUTPUT_CONFLICT, "출력 계약 2 인데 썸네일 key 가 없다");
        }
        String resultSha = sha256(result.full());
        String thumbSha = sha256(result.thumbnailJpeg());
        putIfAbsent(payload.bucket(), payload.resultKey(), format.mimeType(), result.full(), resultSha);
        putIfAbsent(payload.bucket(), payload.thumbnailKey(), "image/jpeg", result.thumbnailJpeg(), thumbSha);
        return new ComposeLambdaResult(true, resultSha, thumbSha, payload.attemptNo(), payload.contract());
    }

    // If-None-Match: * — 이미 있으면 412. 그때는 저장된 객체의 체크섬(ChecksumMode)을 읽어 같은 바이트인지만 본다.
    // SDK 2.25.67 에는 ifNoneMatch() 가 없어 헤더로 싣는다(서버 S3FileStorageService.presignPut 과 같은 방식)
    private void putIfAbsent(String bucket, String key, String contentType, byte[] bytes, String sha256) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key).contentType(contentType)
                            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                            .checksumSHA256(sha256)
                            .overrideConfiguration(o -> o.putHeader("If-None-Match", "*"))
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception e) {
            if (e.statusCode() != 412) {
                throw e;   // 그 밖의 S3 오류는 그대로 — AWS 재시도 대상
            }
            String stored = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build()).checksumSHA256();
            if (!sha256.equals(stored)) {
                throw new ComposeFailureException(ComposeFailureCode.OUTPUT_CONFLICT,
                        "이미 다른 바이트가 저장돼 있다(또는 체크섬이 없다): " + key);
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
