package com.backendsystemdesignlab.notification.notification.dto;

import com.backendsystemdesignlab.notification.notification.domain.NotificationStatus;

public record SendNotificationResponse(
        Long notificationId,
        NotificationStatus status,
        long deliveryCount
) {
}
