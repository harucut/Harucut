package com.harucut.participant;

import com.harucut.participant.controller.ParticipantController;
import com.harucut.participant.dto.ParticipantDtos;
import com.harucut.participant.security.ParticipantPrincipal;
import com.harucut.participant.service.ParticipantComposeService;
import com.harucut.participant.service.ParticipantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ParticipantComposeRetryTest {

    @Mock private ParticipantService participantService;
    @Mock private ParticipantComposeService composeService;
    @InjectMocks private ParticipantController controller;

    @Test
    @DisplayName("예약 교착은 전체 접수를 다시 한다(R5 L454, O40) — 시도마다 새 트랜잭션")
    void retriesDeadlock() {
        ParticipantDtos.ComposeAccepted accepted = mock(ParticipantDtos.ComposeAccepted.class);
        given(composeService.submit(any(), any(), any()))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willReturn(accepted);

        assertThat(controller.compose(mock(ParticipantPrincipal.class), "e", mock(ParticipantDtos.ComposeRequest.class)).data())
                .isSameAs(accepted);
        then(composeService).should(times(3)).submit(any(), any(), any());
    }

    @Test
    @DisplayName("세 번 다 교착이면 그대로 올린다 — 끝없이 돌지 않는다")
    void givesUpAfterThree() {
        given(composeService.submit(any(), any(), any())).willThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> controller.compose(mock(ParticipantPrincipal.class), "e", mock(ParticipantDtos.ComposeRequest.class)))
                .isInstanceOf(CannotAcquireLockException.class);
        then(composeService).should(times(3)).submit(any(), any(), any());
    }
}
