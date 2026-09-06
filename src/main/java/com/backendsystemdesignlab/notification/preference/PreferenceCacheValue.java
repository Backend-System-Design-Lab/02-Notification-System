package com.backendsystemdesignlab.notification.preference;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;

import java.util.Set;

public record PreferenceCacheValue(
        Set<NotificationChannel> enabledChannels
) {
}
