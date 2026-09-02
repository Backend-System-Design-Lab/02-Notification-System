package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.dto.DeliveryCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DeliveryPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishAll(Long notificationId, List<DeliveryCommand> deliveries) {
        for (DeliveryCommand delivery : deliveries) {
            publish(notificationId, delivery);
        }
    }

    private void publish(Long notificationId, DeliveryCommand delivery) {
        DeliveryMessage message = new DeliveryMessage(notificationId, delivery.deliveryId(), delivery.channel(), delivery.destination(), 1);

        String routingKey = switch (delivery.channel()) {
            case PUSH -> RabbitMqConfig.PUSH_ROUTING_KEY;
            case SMS -> RabbitMqConfig.SMS_ROUTING_KEY;
            case EMAIL -> RabbitMqConfig.EMAIL_ROUTING_KEY;
        };

        rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, message);
    }
}
