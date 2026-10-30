package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRecoverySchedulerTest {

    @Test
    void expiredDeliveryShouldTriggerRecovery() {
        var repository = mock(NotificationDeliveryRepository.class);
        var recoveryService = mock(DeliveryRecoveryTransactionService.class);
        var delivery = mock(NotificationDelivery.class);

        when(delivery.getId()).thenReturn(10L);

        when(repository
                .findTop100ByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
                        eq(DeliveryStatus.PROCESSING),
                        any(LocalDateTime.class)
                ))
                .thenReturn(List.of(delivery));

        when(recoveryService.recoverIfExpired(
                eq(10L), any(LocalDateTime.class)
        )).thenReturn(true);

        var scheduler = new DeliveryRecoveryScheduler(
                repository, recoveryService, 300
        );

        scheduler.recoverExpired();

        verify(recoveryService, times(1))
                .recoverIfExpired(eq(10L), any(LocalDateTime.class));
    }
}
