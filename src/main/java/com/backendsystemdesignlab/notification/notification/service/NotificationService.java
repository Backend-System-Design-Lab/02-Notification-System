package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.dedup.NotificationDedupCache;
import com.backendsystemdesignlab.notification.notification.dto.*;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationTransactionService transactionService;
    private final NotificationDedupCache dedupCache;

    public SendNotificationResponse send(SendNotificationRequest request) {

        // 1. Redis Fast Path
        var cached = dedupCache.find(request.eventId());

        if (cached.isPresent()) {
            return cached.get();
        }

        // 2. 기존 DB 처리
        PreparedNotification prepared;

        try {
            prepared = transactionService.prepare(request);
        } catch (DataIntegrityViolationException e) {
            return transactionService.findExisingResponse(request.eventId())
                    .orElseThrow(() -> e);
        }
        SendNotificationResponse response;

        // 이미 처리했던 eventId
        if (prepared.alreadyProcessed()) {
            long deliveryCount = transactionService.countDeliveries(prepared.notificationId());
            response = new SendNotificationResponse(
                    prepared.notificationId(),
                    prepared.status(),
                    deliveryCount
            );
        } else {
            response = new SendNotificationResponse(
                    prepared.notificationId(),
                    prepared.status(),
                    prepared.deliveryCount()
            );
        }

        dedupCache.save(request.eventId(), response);

        return response;
    }
}
