package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.provider.EmailProvider;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.PushProvider;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeliveryMessageHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final PushProvider pushProvider;
    private final SmsProvider smsProvider;
    private final EmailProvider emailProvider;

    private final DeliveryRetryPublisher retryPublisher;
    private final NotificationTransactionService transactionService;

    public void handle(DeliveryMessage message) {

        if (transactionService.isAlreadyProcessed(message.deliveryId())) return;

        boolean success = send(message);

        if (success) {
            transactionService.recordSuccess(message.notificationId(), message.deliveryId());
            return;
        }

        if (message.attempt() < MAX_ATTEMPTS) {
            DeliveryMessage retryMessage = message.nextAttempt();
            retryPublisher.publishRetry(retryMessage);
            transactionService.recordRetryFailure(message.deliveryId());
            return;
        }

        retryPublisher.publishDlq(message);
        transactionService.recordFinalFailure(message.notificationId(), message.deliveryId());
    }

    private boolean send(DeliveryMessage message) {

        String idempotencyKey = "notification-delivery-" + message.deliveryId();

        try {
            ProviderResult result =
                    switch (message.channel()) {
                        case PUSH -> pushProvider.send(message.destination(), idempotencyKey);
                        case SMS -> smsProvider.send(message.destination(), idempotencyKey);
                        case EMAIL -> emailProvider.send(message.destination(), idempotencyKey);
                    };
            return result.success();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
