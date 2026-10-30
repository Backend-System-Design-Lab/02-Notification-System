package com.backendsystemdesignlab.notification.messaging.consumer;

import com.backendsystemdesignlab.notification.messaging.DeliveryMessage;
import com.backendsystemdesignlab.notification.messaging.DeliveryMessageHandler;
import com.backendsystemdesignlab.notification.messaging.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "notification.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class EmailDeliveryConsumer {

    private final DeliveryMessageHandler messageHandler;

    @RabbitListener(
            queues = RabbitMqConfig.EMAIL_QUEUE,
            concurrency = "${NOTIFICATION_CONSUMER_CONCURRENCY:1}"
    )
    public void consume(DeliveryMessage message) {
        messageHandler.handle(message);
    }
}
