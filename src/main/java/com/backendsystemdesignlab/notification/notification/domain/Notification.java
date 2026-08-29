package com.backendsystemdesignlab.notification.notification.domain;

import com.backendsystemdesignlab.notification.user.domain.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "notifications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_notification_event_id",
                        columnNames = "event_id"
                )
        }
)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {}

    public Notification(String eventId, User user) {
        this.eventId = eventId;
        this.user = user;
        this.status = NotificationStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void startProcessing() {
        this.status = NotificationStatus.PROCESSING;
    }

    public void complete() {
        this.status = NotificationStatus.COMPLETED;
    }

    public void fail() {
        this.status = NotificationStatus.FAILED;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public String getEventId() {
        return eventId;
    }

    public User getUser() {
        return user;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
