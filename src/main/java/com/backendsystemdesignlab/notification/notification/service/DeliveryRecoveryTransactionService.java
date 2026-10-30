package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import com.backendsystemdesignlab.notification.notification.repository.NotificationRepository;
import com.backendsystemdesignlab.notification.outbox.OutboxEvent;
import com.backendsystemdesignlab.notification.outbox.OutboxEventRepository;
import com.backendsystemdesignlab.notification.outbox.OutboxRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryRecoveryTransactionService {

    private static final int MAX_ATTEMPTS = 3;

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public boolean recoverIfExpired(Long deliveryId, LocalDateTime cutoff) {

        // 1. 복구 대상 Delivery에 대한 배타적 잠금
        NotificationDelivery snapshot = deliveryRepository
                .findByIdForUpdate(deliveryId)
                .orElse(null);

        if (snapshot == null) {
            return false;
        }

        Long notificationId = snapshot.getNotification().getId();

        // 2. Notification → Delivery 순서로 잠금 획득
        Notification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElseThrow();

        NotificationDelivery delivery = deliveryRepository
                .findByIdForUpdate(deliveryId)
                .orElseThrow();

        // 3. 현재 상태 재검사
        if (delivery.getStatus() != DeliveryStatus.PROCESSING) {
            return false;
        }

        LocalDateTime startedAt = delivery.getProcessingStartedAt();

        if (startedAt == null || !startedAt.isBefore(cutoff)) {
            return false;
        }

        // 4. 최대 시도 횟수를 소진한 경우
        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {

            delivery.markFailed();
            updateNotificationStatus(notification);

            outboxEventRepository.save(
                    new OutboxEvent(
                            UUID.randomUUID().toString(),
                            notificationId,
                            deliveryId,
                            delivery.getChannel(),
                            delivery.getDestination(),
                            delivery.getAttemptCount(),
                            OutboxRoute.DLQ
                    )
            );

            return true;
        }

        // 5. 재처리 가능한 경우
        int nextAttempt = delivery.getAttemptCount() + 1;

        delivery.returnToPending();

        // 6. 재발행을 위한 Outbox 저장
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                delivery.getNotification().getId(),
                delivery.getId(),
                delivery.getChannel(),
                delivery.getDestination(),
                nextAttempt
        );

        outboxEventRepository.save(event);

        return true;
    }

    private void updateNotificationStatus(Notification notification) {

        deliveryRepository.flush();

        Long notificationId = notification.getId();

        long total = deliveryRepository.countByNotificationId(notificationId);

        long sent = deliveryRepository.countByNotificationIdAndStatus(notificationId, DeliveryStatus.SENT);

        long failed = deliveryRepository.countByNotificationIdAndStatus(notificationId, DeliveryStatus.FAILED);

        // 아직 처리 중인 Delivery가 존재
        if (sent + failed < total) {
            return;
        }

        if (failed > 0) {
            notification.fail();
        } else {
            notification.complete();
        }
    }
}
