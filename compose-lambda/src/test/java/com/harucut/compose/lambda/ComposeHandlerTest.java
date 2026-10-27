package com.harucut.compose.lambda;

import com.harucut.frame.attributes.BackgroundAttributes;
import com.harucut.frame.layout.FrameLayout.Slot;
import com.harucut.media.compose.ComposeFailureCode;
import com.harucut.media.compose.ComposeFailureException;
import com.harucut.media.compose.ComposeLambdaPayload;
import com.harucut.media.compose.ComposeSpec;
import com.harucut.media.compose.ImageFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComposeHandler")
class ComposeHandlerTest {

    // 서버(LambdaComposeExecutor)와 같은 잭슨 — payload 계약의 wire 검증
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String BUCKET = "harucut-test";
    private static final String RESULT_KEY = "uploads/users/abc/fourcuts/job-5.png";
    private static final String THUMB_KEY = "uploads/users/abc/fourcuts/job-5-thumb.jpg";
    private static final List<String> SOURCE_KEYS = List.of(
            "uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg", "uploads/u/4.jpg");
    private static final Long JOB_ID = 5L;

    @Mock
    private S3Client s3Client;

    @Test
    @DisplayName("서버 잭슨이 만든 payload(다형성 IMAGE 배경 포함)를 읽어 그리고 결과를 올린다")
    void roundTripWithPolymorphicBackground() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Image("uploads/u/bg.png", 0.8, null),
                fourSlots(), List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new ComposeHandler(s3Client).handleRequest(
                payloadStream(spec, ImageFormat.PNG), output, null);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.captor();
        then(s3Client).should(times(2)).putObject(requestCaptor.capture(), bodyCaptor.capture());
        PutObjectRequest resultPut = requestCaptor.getAllValues().get(0);
        assertThat(resultPut.bucket()).isEqualTo(BUCKET);
        assertThat(resultPut.key()).isEqualTo(RESULT_KEY);
        assertThat(resultPut.contentType()).isEqualTo("image/png");
        BufferedImage result = ImageIO.read(
                bodyCaptor.getAllValues().get(0).contentStreamProvider().newStream());
        assertThat(result.getWidth()).isEqualTo(40);
        assertThat(result.getHeight()).isEqualTo(40);
        // 두 번째 업로드가 썸네일 — 40px 캔버스는 512보다 작아 크기가 그대로다
        PutObjectRequest thumbPut = requestCaptor.getAllValues().get(1);
        assertThat(thumbPut.key()).isEqualTo(THUMB_KEY);
        assertThat(thumbPut.contentType()).isEqualTo("image/jpeg");
        BufferedImage thumb = ImageIO.read(
                bodyCaptor.getAllValues().get(1).contentStreamProvider().newStream());
        assertThat(thumb.getWidth()).isEqualTo(40);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("자유 사진 칸(ADR-0012) payload — 서버 잭슨이 실은 cells 를 읽어 모양대로 자른다(타원 밖 모서리는 배경)")
    void freeCellsPayload() throws IOException {
        List<Slot> big = List.of(new Slot(0, 0, 40, 40), new Slot(0, 0, 1, 1), new Slot(1, 0, 1, 1), new Slot(2, 0, 1, 1));
        ComposeSpec spec = new ComposeSpec(40, 40, new BackgroundAttributes.Color("#FFFFFF"), big,
                List.of(false, false, false, false), List.of(), List.of(
                new ComposeSpec.Cell(0, ComposeSpec.CellShape.ELLIPSE, 0, 3),
                new ComposeSpec.Cell(0, ComposeSpec.CellShape.RECT, 0, 2),
                new ComposeSpec.Cell(0, ComposeSpec.CellShape.RECT, 0, 1),
                new ComposeSpec.Cell(0, ComposeSpec.CellShape.RECT, 0, 0)));
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));

        new ComposeHandler(s3Client).handleRequest(payloadStream(spec, ImageFormat.PNG), new ByteArrayOutputStream(), null);

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.captor();
        then(s3Client).should(times(2)).putObject(any(PutObjectRequest.class), bodyCaptor.capture());
        BufferedImage result = ImageIO.read(bodyCaptor.getAllValues().get(0).contentStreamProvider().newStream());
        assertThat(new Color(result.getRGB(20, 20))).isEqualTo(Color.RED);
        assertThat(new Color(result.getRGB(39, 39))).isEqualTo(Color.WHITE);
    }

    @Test
    @DisplayName("원본이 저장소에 없으면 [SOURCE_MISSING] 머리말을 단 실패로 끝난다 — 실패 통지의 errorMessage 로 종류가 서버에 간다")
    void missingSourceIsCoded() {
        ComposeSpec spec = new ComposeSpec(40, 40, new BackgroundAttributes.Color("#FFFFFF"), fourSlots(),
                List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willThrow(NoSuchKeyException.builder().message("The specified key does not exist.").build());

        assertThatThrownBy(() -> new ComposeHandler(s3Client).handleRequest(payloadStream(spec, ImageFormat.PNG),
                new ByteArrayOutputStream(), null))
                .isInstanceOfSatisfying(ComposeFailureException.class, e -> {
                    assertThat(e.code()).isEqualTo(ComposeFailureCode.SOURCE_MISSING);
                    assertThat(e.getMessage()).startsWith("[SOURCE_MISSING] ");
                });
    }

    @Test
    @DisplayName("JPEG 를 지시하면 바이트도 contentType 도 JPEG 다 — 셋이 어긋나면 안 된다")
    void honoursJpegOutputFormat() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Color("#FFFFFF"),
                fourSlots(), List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));

        new ComposeHandler(s3Client).handleRequest(
                payloadStream(spec, ImageFormat.JPEG), new ByteArrayOutputStream(), null);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.captor();
        then(s3Client).should(times(2)).putObject(requestCaptor.capture(), bodyCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(magic(bodyCaptor.getAllValues().get(0))).isEqualTo(JPEG_MAGIC);
        // 썸네일은 포맷 지시와 무관하게 늘 JPEG 다 — 목록 그리드 전용이라 고를 이유가 없다
        assertThat(requestCaptor.getAllValues().get(1).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("outputFormat 없는 옛 payload는 PNG 로 떨어진다 — Lambda 선배포 안전장치")
    void legacyPayloadWithoutOutputFormat() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Color("#FFFFFF"),
                fourSlots(), List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));
        // 필드 자체가 없는 JSON — 포맷 도입 전 서버의 wire 포맷 그대로.
        // 이게 깨지면 옛 서버가 보낸 .png 키에 JPEG 바이트가 들어간다
        String legacyJson = MAPPER.writeValueAsString(Map.of(
                "bucket", BUCKET, "spec", spec, "sourceKeys", SOURCE_KEYS,
                "resultKey", RESULT_KEY, "thumbnailKey", THUMB_KEY));

        new ComposeHandler(s3Client).handleRequest(
                new ByteArrayInputStream(legacyJson.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), null);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.captor();
        then(s3Client).should(times(2)).putObject(requestCaptor.capture(), bodyCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(0).contentType()).isEqualTo("image/png");
        assertThat(magic(bodyCaptor.getAllValues().get(0))).isEqualTo(PNG_MAGIC);
    }

    @Test
    @DisplayName("thumbnailKey 없는 옛 payload는 원본만 올린다 — 서버보다 먼저 배포해도 안전")
    void legacyPayloadWithoutThumbnailKey() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Color("#FFFFFF"),
                fourSlots(), List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));
        // 필드 자체가 없는 JSON — 썸네일 도입 전 서버의 wire 포맷 그대로
        String legacyJson = MAPPER.writeValueAsString(Map.of(
                "bucket", BUCKET, "spec", spec, "sourceKeys", SOURCE_KEYS, "resultKey", RESULT_KEY));

        new ComposeHandler(s3Client).handleRequest(
                new ByteArrayInputStream(legacyJson.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), null);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.captor();
        then(s3Client).should(times(1)).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).isEqualTo(RESULT_KEY);
    }

    @Test
    @DisplayName("jobId 없는 옛 payload도 그대로 그린다 — Lambda를 서버보다 먼저 배포해도 안전")
    void legacyPayloadWithoutJobId() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Color("#FFFFFF"),
                fourSlots(), List.of(false, false, false, false), List.of());
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));
        // jobId 도입 전 서버의 wire 포맷. 핸들러는 이 값을 읽지 않으므로 없어도 결과가 같아야 한다 —
        // 이게 "Lambda 를 서버보다 먼저 배포한다"는 규칙을 코드로 고정한다
        String legacyJson = MAPPER.writeValueAsString(Map.of(
                "bucket", BUCKET, "spec", spec, "sourceKeys", SOURCE_KEYS,
                "resultKey", RESULT_KEY, "thumbnailKey", THUMB_KEY));

        new ComposeHandler(s3Client).handleRequest(
                new ByteArrayInputStream(legacyJson.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), null);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.captor();
        then(s3Client).should(times(2)).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(RESULT_KEY, THUMB_KEY);
    }

    @Test
    @DisplayName("원본 4장과 스펙이 참조하는 자산(배경·레이어)을 전부 내려받는다")
    void downloadsSourcesAndAssets() throws IOException {
        ComposeSpec spec = new ComposeSpec(40, 40,
                new BackgroundAttributes.Image("uploads/u/bg.png", 1.0, null),
                fourSlots(), List.of(false, false, false, false),
                List.of(new ComposeSpec.Layer("uploads/u/sticker.png",
                        0, 0, 10, 10, 1.0, 0, 1, 1.0)));
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), solidPng(10, 10, Color.RED)));

        new ComposeHandler(s3Client).handleRequest(
                payloadStream(spec, ImageFormat.PNG), new ByteArrayOutputStream(), null);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.captor();
        then(s3Client).should(times(6)).getObjectAsBytes(captor.capture());
        assertThat(captor.getAllValues()).extracting(GetObjectRequest::key)
                .containsExactly("uploads/u/1.jpg", "uploads/u/2.jpg", "uploads/u/3.jpg",
                        "uploads/u/4.jpg", "uploads/u/bg.png", "uploads/u/sticker.png");
        assertThat(captor.getAllValues()).extracting(GetObjectRequest::bucket)
                .allMatch(BUCKET::equals);
    }

    // ── fixtures ──────────────────────────────

    private static ByteArrayInputStream payloadStream(ComposeSpec spec, ImageFormat format) {
        String json = MAPPER.writeValueAsString(new ComposeLambdaPayload(
                BUCKET, JOB_ID, spec, SOURCE_KEYS, RESULT_KEY, THUMB_KEY, format));
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    // 확장자·contentType 이 아니라 바이트 자체를 본다 — 셋이 어긋나는 게 정확히 우리가 막으려는 사고다
    private static byte[] magic(RequestBody body) {
        try (var stream = body.contentStreamProvider().newStream()) {
            return stream.readNBytes(4);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private static List<Slot> fourSlots() {
        return List.of(new Slot(0, 0, 10, 10), new Slot(10, 0, 10, 10),
                new Slot(0, 10, 10, 10), new Slot(10, 10, 10, 10));
    }

    private static byte[] solidPng(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
