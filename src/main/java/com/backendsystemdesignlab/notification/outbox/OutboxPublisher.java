package com.backendsystemdesignlab.notification.outbox;

import com.backendsystemdesignlab.notification.messaging.DeliveryMessage;
import com.backendsystemdesignlab.notification.messaging.RabbitMqConfig;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxTransactionService transactionService;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationTransactionService notificationTransactionService;
    private final OutboxFailureService failureService;

    // DB Connection 없음 (RabbitMQ가 DB를 잡고 있지 않게)
    @Scheduled(
            fixedDelayString = "${outbox.publish-interval-ms:1000}"
    )
    public void publishPendingEvents() {
        List<OutboxEvent> events = transactionService.findPendingEvents();

        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            DeliveryMessage message = new DeliveryMessage(
                    event.getNotificationId(),
                    event.getDeliveryId(),
                    event.getChannel(),
                    event.getDestination(),
                    1
            );

            CorrelationData correlationData = new CorrelationData(String.valueOf(event.getId()));

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE,
                    routingKey(event),
                    message,
                    correlationData
            );

            // Spring AMQP < CorrelationData < CompletableFuture (Confirm의 결과)
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS); // 5초 기다림

            if (!confirm.ack()) { // RabbitMQ Broker가 잘 받았는지 (Publisher -> Broker)
                handleFailure(event, "NACK: " + confirm.reason());
                return;
            }

            if (correlationData.getReturned() != null) { // Broker Exchange -> Queue로 라우팅됐는가? (라우팅 실패) 예: 잘못된 라우팅키
                String error = "RETURN: " + correlationData.getReturned().getReplyText();
                handleFailure(event, error);
                return;
            }


            // @Transaction
            transactionService.markPublished(event.getId());

            log.debug("Outbox published. outboxId={}, deliveryId={}", event.getId(), event.getDeliveryId());
        } catch (TimeoutException e) {
            handleFailure(event, "CONFIRM_TIMEOUT");
        } catch (Exception e) {
            handleFailure(event, e.getMessage());
        }
    }

    private String routingKey(OutboxEvent event) {
        return switch (event.getChannel()) {
            case PUSH ->
                    RabbitMqConfig.PUSH_ROUTING_KEY;

            case SMS ->
                    RabbitMqConfig.SMS_ROUTING_KEY;

            case EMAIL ->
                    RabbitMqConfig.EMAIL_ROUTING_KEY;
        };
    }

    private void handleFailure(OutboxEvent event, String reason) {

        boolean finalFailure = failureService.recordFailure(event.getId(), reason);

        if (finalFailure) {
            log.error("Outbox 마지막 시도 실패. outboxId={}, reason={}", event.getId(), reason);
        } else {
            log.warn("Outbox publish 실패.  outboxId={}, reason={}", event.getId(), reason);
        }
    }
}
