package com.backendsystemdesignlab.notification.notification.dto;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;

public record DeliveryCommand(
        Long deliveryId,
        NotificationChannel channel,
        String destination
) {
}
