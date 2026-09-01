package com.backendsystemdesignlab.notification.messaging.consumer;

import com.backendsystemdesignlab.notification.messaging.DeliveryMessage;
import com.backendsystemdesignlab.notification.messaging.RabbitMqConfig;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmsDeliveryConsumer {

    private final SmsProvider smsProvider;
    private final NotificationTransactionService transactionService;

    @RabbitListener(
            queues = RabbitMqConfig.SMS_QUEUE,
            concurrency = "${NOTIFICATION_CONSUMER_CONCURRENCY:1}"
    )
    public void consume(DeliveryMessage message) {

        boolean success;

        try {
            ProviderResult result = smsProvider.send(message.destination());
            success = result.success();
        } catch (RuntimeException e) {
            success = false;
        }

        transactionService.recordDeliveryResult(
                message.notificationId(),
                message.deliveryId(),
                success
        );
    }

}
