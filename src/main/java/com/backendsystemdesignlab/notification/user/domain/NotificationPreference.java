package com.backendsystemdesignlab.notification.user.domain;

import jakarta.persistence.*;

@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_channel",
                        columnNames = {"user_id", "channel"}
                )
        }
)
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    protected NotificationPreference() {}

    public NotificationPreference(User user, NotificationChannel channel, boolean enabled) {
        this.user = user;
        this.channel = channel;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
