package com.harucut.storage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("S3MultipartOutputStream")
class S3MultipartOutputStreamTest {

    private S3Client s3Client;
    private final List<byte[]> parts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        given(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .willReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        given(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).willAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            parts.add(body.contentStreamProvider().newStream().readAllBytes());
            return UploadPartResponse.builder().eTag("etag-" + parts.size()).build();
        });
    }

    @Test
    @DisplayName("파트 크기마다 올리고 마지막 남은 것까지 올린 뒤 순서대로 완료한다")
    void uploadsPartsInOrder() throws IOException {
        S3MultipartOutputStream out = new S3MultipartOutputStream(s3Client, "bucket", "k.zip", "application/zip", 8);
        out.write("0123456789ABCDEFGHIJ".getBytes());   // 20바이트 → 8 + 8 + 4
        out.close();

        assertThat(parts).extracting(String::new).containsExactly("01234567", "89ABCDEF", "GHIJ");
        ArgumentCaptor<CompleteMultipartUploadRequest> complete = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        then(s3Client).should().completeMultipartUpload(complete.capture());
        assertThat(complete.getValue().uploadId()).isEqualTo("up-1");
        assertThat(complete.getValue().multipartUpload().parts()).extracting(p -> p.partNumber() + ":" + p.eTag())
                .containsExactly("1:etag-1", "2:etag-2", "3:etag-3");
    }

    @Test
    @DisplayName("빈 묶음도 파트 하나로 완료한다, 한 바이트씩 써도 같다")
    void emptyAndSingleBytes() throws IOException {
        new S3MultipartOutputStream(s3Client, "bucket", "empty.zip", "application/zip", 8).close();
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).isEmpty();

        parts.clear();
        S3MultipartOutputStream out = new S3MultipartOutputStream(s3Client, "bucket", "b.zip", "application/zip", 4);
        for (int i = 0; i < 9; i++) {
            out.write('a' + i);
        }
        out.close();
        assertThat(parts).extracting(String::new).containsExactly("abcd", "efgh", "i");
    }

    @Test
    @DisplayName("완료가 실패하면 업로드를 취소하고 IOException, abort 뒤 close 는 아무것도 하지 않는다")
    void abortsOnFailure() throws IOException {
        given(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).willThrow(new RuntimeException("boom"));
        S3MultipartOutputStream out = new S3MultipartOutputStream(s3Client, "bucket", "k.zip", "application/zip", 8);
        out.write(new byte[3]);

        assertThatThrownBy(out::close).isInstanceOf(IOException.class);
        then(s3Client).should().abortMultipartUpload(any(AbortMultipartUploadRequest.class));

        S3MultipartOutputStream aborted = new S3MultipartOutputStream(s3Client, "bucket", "k2.zip", "application/zip", 8);
        aborted.abort();
        aborted.close();
        assertThatThrownBy(() -> aborted.write(1)).isInstanceOf(IOException.class);
        then(s3Client).should(never()).completeMultipartUpload(ArgumentMatchers.<CompleteMultipartUploadRequest>argThat(
                r -> r != null && "k2.zip".equals(r.key())));
    }
}
