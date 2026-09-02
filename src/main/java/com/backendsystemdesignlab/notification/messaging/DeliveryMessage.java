package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;

public record DeliveryMessage(
        Long notificationId,
        Long deliveryId,
        NotificationChannel channel,
        String destination,
        int attempt
) {

    public DeliveryMessage nextAttempt() {
        return new DeliveryMessage(
                notificationId,
                deliveryId,
                channel,
                destination,
                attempt + 1
        );
    }
}
