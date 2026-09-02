package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.messaging.DeliveryPublisher;
import com.backendsystemdesignlab.notification.notification.dto.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationTransactionService transactionService;
    private final DeliveryPublisher deliveryPublisher;

    public SendNotificationResponse send(SendNotificationRequest request) {

        // DB 작업
        PreparedNotification prepared = transactionService.prepare(request);

        // 이미 처리했던 eventId
        if (prepared.alreadyProcessed()) {
            long deliveryCount = transactionService.countDeliveries(prepared.notificationId());
            return new SendNotificationResponse(
                    prepared.notificationId(),
                    prepared.status(),
                    deliveryCount
            );
        }

//        deliveryPublisher.publishAll(prepared.notificationId(), prepared.deliveries());

        return new SendNotificationResponse(
                prepared.notificationId(),
                prepared.status(), // PROCESSING 비동기 이기 때문에 아직 Provider 전송이 안끝남
                prepared.deliveryCount()
        );

    }
}
