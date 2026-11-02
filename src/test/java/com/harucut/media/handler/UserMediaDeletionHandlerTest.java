package com.harucut.media.handler;

import com.harucut.media.entity.ComposeJob;
import com.harucut.media.enums.ComposeStatus;
import com.harucut.media.repository.ComposeJobRepository;
import com.harucut.media.repository.UserMediaRepository;
import com.harucut.media.service.ComposeService;
import com.harucut.storage.service.S3Deleter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserMediaDeletionHandler")
class UserMediaDeletionHandlerTest {

    @Mock
    private UserMediaRepository userMediaRepository;

    @Mock
    private ComposeJobRepository composeJobRepository;

    @Mock
    private S3Deleter s3Deleter;

    @InjectMocks
    private UserMediaDeletionHandler handler;

    private static ComposeJob job(long id, ComposeStatus status, String resultKey, List<String> inputs) {
        ComposeJob job = mock(ComposeJob.class);
        given(job.getId()).willReturn(id);
        given(job.getStorageRoot()).willReturn("uploads/users/u/");
        given(job.getResultKey()).willReturn(resultKey);
        given(job.getStatus()).willReturn(status);
        if (status == ComposeStatus.PENDING) {
            given(job.inputKeys()).willReturn(inputs);
        }
        return job;
    }

    @Test
    @DisplayName("보관함·작업의 결과는 물론 진행 중인 작업의 결과 자리·입력까지 삭제 기록을 남기고, 늦게 쓰인 것을 위해 재삭제를 건다")
    void recordsAllKeysWithRedelete() {
        given(userMediaRepository.findS3KeysByUserId(1L)).willReturn(List.of("uploads/users/u/m1.jpg"));
        given(userMediaRepository.findThumbnailKeysByUserId(1L)).willReturn(List.of("uploads/users/u/m1-thumb.jpg"));
        ComposeJob done = job(7L, ComposeStatus.DONE, "uploads/users/u/fourcuts/job-7.jpg", List.of());
        ComposeJob pending = job(8L, ComposeStatus.PENDING, null, List.of("uploads/users/u/fourcuts/sources/a.jpg"));
        given(composeJobRepository.findAllByUserId(1L)).willReturn(List.of(done, pending));

        handler.handleUserDeletion(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        then(s3Deleter).should().deleteAfterCommit(keys.capture(), eq(ComposeService.LATE_WRITE_WINDOW));
        assertThat(keys.getValue()).contains(
                "uploads/users/u/m1.jpg", "uploads/users/u/m1-thumb.jpg",
                "uploads/users/u/fourcuts/job-7.jpg", "uploads/users/u/fourcuts/job-7-thumb.jpg",
                "uploads/users/u/fourcuts/job-8.jpg", "uploads/users/u/fourcuts/job-8-thumb.jpg",
                "uploads/users/u/fourcuts/sources/a.jpg");
        then(composeJobRepository).should().deleteByUserId(1L);
        then(userMediaRepository).should().deleteByUserId(1L);
    }
}
