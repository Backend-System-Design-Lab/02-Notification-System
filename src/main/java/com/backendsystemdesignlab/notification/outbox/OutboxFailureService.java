package com.backendsystemdesignlab.notification.outbox;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import com.backendsystemdesignlab.notification.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxFailureService {

    private static final int MAX_PUBLISH_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;

    @Transactional
    public boolean recordFailure(Long outboxEventId, String error) {

        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + outboxEventId));

        if (event.getStatus() != OutboxStatus.PENDING) {
            return false;
        }

        event.recordFailure(error);

        if (event.getAttemptCount() < MAX_PUBLISH_ATTEMPTS) {
            return false;
        }

        event.markFailed();

        Notification notification = notificationRepository.findByIdForUpdate(event.getNotificationId())
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(event.getDeliveryId())
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() == DeliveryStatus.PENDING) {
            delivery.markFailed();
        }

        updateNotificationStatus(notification);

        return true;
    }

    private void updateNotificationStatus(Notification notification) {
        deliveryRepository.flush();

        long total = deliveryRepository.countByNotificationId(notification.getId());
        long sent = deliveryRepository.countByNotificationIdAndStatus(notification.getId(), DeliveryStatus.SENT);
        long failed = deliveryRepository.countByNotificationIdAndStatus(notification.getId(), DeliveryStatus.FAILED);

        // 아직 처리 중인 Delivery 존재
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
