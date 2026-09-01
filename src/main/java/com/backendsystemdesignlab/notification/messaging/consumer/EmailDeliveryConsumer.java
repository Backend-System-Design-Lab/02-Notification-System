package com.backendsystemdesignlab.notification.messaging.consumer;

import com.backendsystemdesignlab.notification.messaging.DeliveryMessage;
import com.backendsystemdesignlab.notification.messaging.RabbitMqConfig;
import com.backendsystemdesignlab.notification.notification.provider.EmailProvider;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailDeliveryConsumer {

    private final EmailProvider emailProvider;
    private final NotificationTransactionService transactionService;

    @RabbitListener(queues = RabbitMqConfig.EMAIL_QUEUE)
    public void consume(DeliveryMessage message) {

        boolean success;

        try {
            ProviderResult result = emailProvider.send(message.destination());
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
