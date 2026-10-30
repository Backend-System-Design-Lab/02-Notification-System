package com.backendsystemdesignlab.notification.notification.domain;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Getter
    @Column(nullable = false, length = 512)
    private String destination;

    @Getter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Getter
    @Column(nullable = false)
    private int attemptCount;

    @Getter
    private LocalDateTime sentAt;

    @Getter
    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Getter
    @Column(name = "processing_token", length = 36)
    private String processingToken;

    protected NotificationDelivery() {}

    public NotificationDelivery(Notification notification, NotificationChannel channel, String destination) {
        this.notification = notification;
        this.channel = channel;
        this.destination = destination;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
    }

    public void returnToPending() {
        this.status = DeliveryStatus.PENDING;
        this.processingStartedAt = null;
        this.processingToken = null;
    }

    public void recordAttempt() {
        this.attemptCount++;
    }

    public void markSent() {
        this.status = DeliveryStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.processingStartedAt = null;
        this.processingToken = null;
    }

    public void markFailed() {
        this.status = DeliveryStatus.FAILED;
        this.processingStartedAt = null;
        this.processingToken = null;
    }

    public boolean isClaimedBy(String token) {
        return status == DeliveryStatus.PROCESSING
                && processingToken != null
                && processingToken.equals(token);
    }

}
