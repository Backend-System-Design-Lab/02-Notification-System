package com.backendsystemdesignlab.notification.notification.domain;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 512)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime sentAt;

    protected NotificationDelivery() {}

    public NotificationDelivery(Notification notification, NotificationChannel channel, String destination) {
        this.notification = notification;
        this.channel = channel;
        this.destination = destination;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
    }

    public void recordAttempt() {
        this.attemptCount++;
    }

    public void markSent() {
        this.status = DeliveryStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = DeliveryStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public Notification getNotification() {
        return notification;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getDestination() {
        return destination;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
