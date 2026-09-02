package com.backendsystemdesignlab.notification.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryRetryPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishRetry(DeliveryMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.RETRY_EXCHANGE,
                routingKey(message),
                message
        );
    }

    public void publishDlq(DeliveryMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.DLX,
                routingKey(message),
                message
        );
    }

    private String routingKey(DeliveryMessage message) {
        return switch (message.channel()) {
            case PUSH -> RabbitMqConfig.PUSH_ROUTING_KEY;
            case SMS -> RabbitMqConfig.SMS_ROUTING_KEY;
            case EMAIL -> RabbitMqConfig.EMAIL_ROUTING_KEY;
        };
    }
}
