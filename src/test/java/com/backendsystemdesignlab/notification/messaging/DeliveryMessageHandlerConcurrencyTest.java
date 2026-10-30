package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.provider.*;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import com.backendsystemdesignlab.notification.ratelimit.*;
import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryMessageHandlerConcurrencyTest {

    @Mock PushProvider pushProvider;
    @Mock SmsProvider smsProvider;
    @Mock EmailProvider emailProvider;
    @Mock DeliveryRetryPublisher retryPublisher;
    @Mock NotificationTransactionService transactionService;
    @Mock NotificationRateLimiter rateLimiter;
    @Mock ThrottlePublisher throttlePublisher;

    @InjectMocks DeliveryMessageHandler handler;

    @Test
    void sameDeliveryShouldCallProviderOnlyOnce() throws Exception {
        long deliveryId = 10L;

        CountDownLatch bothChecked = new CountDownLatch(2);
        AtomicBoolean claimed = new AtomicBoolean(false);

        when(transactionService.isAlreadyProcessed(deliveryId))
                .thenAnswer(invocation -> {
                    bothChecked.countDown();
                    assertTrue(bothChecked.await(5, TimeUnit.SECONDS));
                    return false;
                });

        when(rateLimiter.tryAcquire(NotificationChannel.PUSH))
                .thenReturn(true);

        when(transactionService.tryClaim(deliveryId, 1))
                .thenAnswer(invocation ->
                        claimed.compareAndSet(false, true)
                                ? Optional.of(
                                new NotificationTransactionService.DeliveryClaim(
                                        "test-token", 1
                                )
                        )
                                : Optional.empty()
                );

        when(pushProvider.send(anyString(), anyString()))
                .thenReturn(new ProviderResult(true));

        DeliveryMessage message = new DeliveryMessage(
                1L, deliveryId, NotificationChannel.PUSH, "token", 1
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> handler.handle(message));
            Future<?> second = executor.submit(() -> handler.handle(message));

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(transactionService, times(2))
                .tryClaim(deliveryId, 1);

        verify(pushProvider, times(1))
                .send("token", "notification-delivery-10");

        verify(transactionService, times(1))
                .recordSuccess(1L, deliveryId, "test-token");
    }
}
