package com.backendsystemdesignlab.notification.notification.dto;

import com.backendsystemdesignlab.notification.notification.domain.NotificationStatus;

import java.util.List;

public record PreparedNotification(
        Long notificationId,
        NotificationStatus status,
        List<DeliveryCommand> deliveries,
        boolean alreadyProcessed
) {

    public long deliveryCount() {
        return deliveries.size();
    }
}
