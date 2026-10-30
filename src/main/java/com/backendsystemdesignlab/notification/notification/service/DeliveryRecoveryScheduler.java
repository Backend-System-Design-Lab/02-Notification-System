package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "delivery.recovery",
        name = "enabled",
        havingValue = "true"
)
public class DeliveryRecoveryScheduler {

    private final NotificationDeliveryRepository repository;
    private final DeliveryRecoveryTransactionService recoveryService;
    private final long timeoutSeconds;

    public DeliveryRecoveryScheduler(
            NotificationDeliveryRepository repository,
            DeliveryRecoveryTransactionService recoveryService,
            @Value("${delivery.recovery.timeout-seconds:300}") long timeoutSeconds
    ) {
        this.repository = repository;
        this.recoveryService = recoveryService;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(
            fixedDelayString = "${delivery.recovery.interval-ms:30000}",
            initialDelayString = "${delivery.recovery.initial-delay-ms:30000}"
    )
    public void recoverExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(timeoutSeconds);

        var expired = repository
                .findTop100ByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
                        DeliveryStatus.PROCESSING, cutoff
                );

        int recovered = 0;

        for (var delivery : expired) {
            try {
                if (recoveryService.recoverIfExpired(delivery.getId(), cutoff)) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Delivery recovery failed. id={}", delivery.getId(), e);
            }
        }

        if (recovered > 0) {
            log.info("Recovered deliveries: {}", recovered);
        }
    }
}
