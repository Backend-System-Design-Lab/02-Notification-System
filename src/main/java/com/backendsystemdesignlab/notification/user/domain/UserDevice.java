package com.backendsystemdesignlab.notification.user.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "user_devices")
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(nullable = false, unique = true, length = 512)
    private String deviceToken;

    @Column(nullable = false)
    private boolean active;

    protected UserDevice() {}

    public UserDevice(User user, Platform platform, String deviceToken) {
        this.user = user;
        this.platform = platform;
        this.deviceToken = deviceToken;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }
    public Platform getPlatform() {
        return platform;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public boolean isActive() {
        return active;
    }
}
