package com.backendsystemdesignlab.notification.preference;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @PatchMapping("/{userId}/notification-preferences/{channel}")
    public ResponseEntity<Void> update(@PathVariable Long userId, @PathVariable NotificationChannel channel, @RequestBody UpdatePreferenceRequest request) {
        preferenceService.updatePreference(userId, channel, request.enabled());
        return ResponseEntity.noContent().build();
    }
}
