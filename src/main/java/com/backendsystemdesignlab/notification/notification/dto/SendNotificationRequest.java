package com.backendsystemdesignlab.notification.notification.dto;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record SendNotificationRequest(

        @NotBlank
        String eventId,

        @NotNull
        Long userId,

        @NotEmpty
        Set<NotificationChannel> channels
) {
}
