package com.harucut.storage.service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * S3 multipart 업로드를 OutputStream 으로 — 큰 묶음(ZIP)을 메모리·디스크에 다 모으지 않고 흘려 올린다.
 * 파트 하나(기본 8MB)만 메모리에 둔다. {@link #close()} 가 완료, {@link #abort()} 가 취소다.
 * 실패한 채로 닫으면 업로드를 취소해 미완 파트가 버킷에 남지 않게 한다(수명 규칙도 따로 걸어 두는 편이 좋다).
 */
public class S3MultipartOutputStream extends OutputStream {

    // S3 의 파트 최소 크기는 5MB(마지막 파트 제외)
    static final int PART_SIZE = 8 * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucket;
    private final String key;
    private final String uploadId;
    private final List<CompletedPart> parts = new ArrayList<>();
    private final byte[] buffer;
    private int position;
    private boolean closed;
    private boolean aborted;

    public S3MultipartOutputStream(S3Client s3Client, String bucket, String key, String contentType, int partSize) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.key = key;
        this.buffer = new byte[partSize];
        this.uploadId = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build()).uploadId();
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        buffer[position++] = (byte) b;
        if (position == buffer.length) {
            flushPart();
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        ensureOpen();
        while (length > 0) {
            int chunk = Math.min(length, buffer.length - position);
            System.arraycopy(bytes, offset, buffer, position, chunk);
            position += chunk;
            offset += chunk;
            length -= chunk;
            if (position == buffer.length) {
                flushPart();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (aborted) {
            return;
        }
        try {
            if (position > 0 || parts.isEmpty()) {
                flushPart();   // 빈 묶음이라도 파트 하나는 있어야 완료할 수 있다
            }
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (RuntimeException e) {
            abort();
            throw new IOException("multipart 완료 실패: " + key, e);
        }
    }

    /** 업로드를 취소한다. 이후 close 는 아무것도 하지 않는다 */
    public void abort() {
        if (aborted) {
            return;
        }
        aborted = true;
        closed = true;
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).build());
    }

    private void flushPart() {
        int partNumber = parts.size() + 1;
        String eTag = s3Client.uploadPart(UploadPartRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber).build(),
                RequestBody.fromBytes(Arrays.copyOf(buffer, position))).eTag();
        parts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
        position = 0;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("이미 닫힌 업로드: " + key);
        }
    }
}
