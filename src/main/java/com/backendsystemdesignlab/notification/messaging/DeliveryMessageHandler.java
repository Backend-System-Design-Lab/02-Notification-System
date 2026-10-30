package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.provider.EmailProvider;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.PushProvider;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import com.backendsystemdesignlab.notification.ratelimit.NotificationRateLimiter;
import com.backendsystemdesignlab.notification.ratelimit.ThrottlePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeliveryMessageHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final PushProvider pushProvider;
    private final SmsProvider smsProvider;
    private final EmailProvider emailProvider;

    private final NotificationTransactionService transactionService;

    private final NotificationRateLimiter rateLimiter;
    private final ThrottlePublisher throttlePublisher;

    public void handle(DeliveryMessage message) {

        if (transactionService.isAlreadyProcessed(message.deliveryId())) return;

        if (!rateLimiter.tryAcquire(message.channel())) {
            throttlePublisher.publish(message);
            return;
        }

        Optional<NotificationTransactionService.DeliveryClaim> claimed =
                transactionService.tryClaim(
                        message.deliveryId(),
                        message.attempt()
                );

        if (claimed.isEmpty()) {
            return;
        }

        var claim = claimed.get();
        String token = claim.token();
        int attempt = claim.attempt();

        boolean success = send(message);

        if (success) {
            transactionService.recordSuccess(message.notificationId(), message.deliveryId(), token);
            return;
        }

        if (attempt < MAX_ATTEMPTS) {
            transactionService.recordRetryFailureAndEnqueue(
                    message.notificationId(),
                    message.deliveryId(),
                    token
            );
            return;
        }

        transactionService.recordFinalFailureAndEnqueue(message.notificationId(), message.deliveryId(), token);
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
