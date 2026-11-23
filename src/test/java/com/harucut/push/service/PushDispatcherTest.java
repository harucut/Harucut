package com.harucut.push.service;

import com.harucut.push.repository.DeviceTokenRepository;
import com.harucut.push.sender.PushSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("푸시 디스패처")
class PushDispatcherTest {

    private final DeviceTokenRepository repository = mock(DeviceTokenRepository.class);
    private final PushSender sender = mock(PushSender.class);
    private final PushDispatcher dispatcher = new PushDispatcher(repository, sender, new NoopTransactionManager());

    @Test
    @DisplayName("계정의 기기로 보내고, 받지 않는 기기는 지운다")
    void sendsAndPrunes() {
        PushMessage message = PushMessage.of(PushType.PAYMENT_FAILED);
        given(repository.findTokensByUserId(1L)).willReturn(List.of("ExpoPushToken[a]", "ExpoPushToken[b]"));
        given(sender.send(List.of("ExpoPushToken[a]", "ExpoPushToken[b]"), message)).willReturn(Set.of("ExpoPushToken[b]"));

        dispatcher.dispatch(new PushRequestedEvent(1L, message));

        then(repository).should().deleteByTokens(Set.of("ExpoPushToken[b]"));
    }

    @Test
    @DisplayName("기기가 없으면 보내지 않고, 발송기 예외는 밖으로 내지 않는다")
    void noDevicesAndSwallows() {
        given(repository.findTokensByUserId(1L)).willReturn(List.of());
        dispatcher.dispatch(new PushRequestedEvent(1L, PushMessage.of(PushType.PAYMENT_FAILED)));
        then(sender).should(never()).send(any(), any());

        given(repository.findTokensByUserId(2L)).willReturn(List.of("ExpoPushToken[a]"));
        given(sender.send(any(), any())).willThrow(new IllegalStateException("boom"));
        dispatcher.dispatch(new PushRequestedEvent(2L, PushMessage.of(PushType.PAYMENT_FAILED)));
        then(repository).should(never()).deleteByTokens(any());
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
