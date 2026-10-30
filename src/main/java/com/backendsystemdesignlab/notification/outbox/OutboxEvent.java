package com.backendsystemdesignlab.notification.outbox;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(
                        name = "idx_outbox_status_created_at",
                        columnList = "status, created_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String messageId;

    @Column(nullable = false)
    private Long notificationId;

    @Column(nullable = false)
    private Long deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attemptCount; // RabbitMQ에 발행하다 실패한 횟수

    @Column(name = "delivery_attempt")
    private Integer deliveryAttempt; // 실제 알림 발송의 시도 회차

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_route", length = 20)
    private OutboxRoute publishRoute;

    private String lastError;


    public OutboxEvent(
            String messageId,
            Long notificationId,
            Long deliveryId,
            NotificationChannel channel,
            String destination
    ) {
        this(messageId, notificationId, deliveryId, channel, destination, 1);
    }

    public OutboxEvent(
            String messageId,
            Long notificationId,
            Long deliveryId,
            NotificationChannel channel,
            String destination,
            int deliveryAttempt
    ) {
        this(messageId, notificationId, deliveryId,
                channel, destination, deliveryAttempt, OutboxRoute.MAIN);
    }

    public OutboxEvent(
            String messageId,
            Long notificationId,
            Long deliveryId,
            NotificationChannel channel,
            String destination,
            int deliveryAttempt,
            OutboxRoute publishRoute
    ) {

        if (deliveryAttempt < 1) {
            throw new IllegalArgumentException("deliveryAttempt must be positive");
        }

        this.messageId = messageId;
        this.notificationId = notificationId;
        this.deliveryId = deliveryId;
        this.channel = channel;
        this.destination = destination;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = LocalDateTime.now();
        this.deliveryAttempt = deliveryAttempt;
        this.publishRoute = java.util.Objects.requireNonNull(publishRoute);
    }

    public int getDeliveryAttempt() {
        return deliveryAttempt == null ? 1 : deliveryAttempt;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void recordFailure(String error) {
        this.attemptCount++;
        this.lastError = error;
    }

    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }

    public OutboxRoute getPublishRoute() {
        return publishRoute == null
                ? OutboxRoute.MAIN
                : publishRoute;
    }
}
