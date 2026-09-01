package com.backendsystemdesignlab.notification.notification.controller;

import com.backendsystemdesignlab.notification.notification.dto.SendNotificationRequest;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationResponse;
import com.backendsystemdesignlab.notification.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<SendNotificationResponse> send(@Valid @RequestBody SendNotificationRequest request) {
        SendNotificationResponse response = notificationService.send(request);
        return ResponseEntity.accepted().body(response);
    }

}
