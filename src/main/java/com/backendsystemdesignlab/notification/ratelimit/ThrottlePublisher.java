package com.backendsystemdesignlab.notification.ratelimit;

import com.backendsystemdesignlab.notification.messaging.DeliveryMessage;
import com.backendsystemdesignlab.notification.messaging.RabbitMqConfig;
import com.backendsystemdesignlab.notification.outbox.OutboxEvent;
import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class ThrottlePublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    public void publish(DeliveryMessage message) {

        try {
            CorrelationData correlationData = new CorrelationData("throttle-" + message.deliveryId() + "-" + UUID.randomUUID());

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.THROTTLE_EXCHANGE,
                    routingKey(message.channel()),
                    message,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS); // 5초 기다림

            if (!confirm.ack()) { // RabbitMQ Broker가 잘 받았는지 (Publisher -> Broker)
                throw new IllegalStateException("Throttle publish NACK. deliveryId=" + message.deliveryId() + ", reason=" + confirm.reason());
            }

            if (correlationData.getReturned() != null) { // Broker Exchange -> Queue로 라우팅됐는가? (라우팅 실패) 예: 잘못된 라우팅키
                throw new IllegalStateException("Throttle publish RETURN. deliveryId=" + message.deliveryId() + ", reason=" + correlationData.getReturned().getReplyText());
            }
        } catch (TimeoutException e) {
            throw new IllegalStateException("Throttle publisher confirm timeout. deliveryId=" + message.deliveryId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thorttle publisher interrupted. deliveryId=" + message.deliveryId(), e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }

            throw new IllegalStateException("Throttle publish failed. deliveryId=" + message.deliveryId(), e);
        }
    }

    private String routingKey(NotificationChannel channel) {
        return switch (channel) {
            case PUSH -> RabbitMqConfig.PUSH_THROTTLE_ROUTING_KEY;
            case SMS -> RabbitMqConfig.SMS_THROTTLE_ROUTING_KEY;
            case EMAIL -> RabbitMqConfig.EMAIL_THROTTLE_ROUTING_KEY;
        };
    }
}
