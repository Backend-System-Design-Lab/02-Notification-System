package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.NotificationStatus;
import com.backendsystemdesignlab.notification.notification.dto.*;
import com.backendsystemdesignlab.notification.notification.provider.EmailProvider;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.PushProvider;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationTransactionService transactionService;

    private final PushProvider pushProvider;
    private final SmsProvider smsProvider;
    private final EmailProvider emailProvider;


    public SendNotificationResponse send(SendNotificationRequest request) {

        // DB 작업
        PreparedNotification prepared = transactionService.prepare(request);

        // 이미 처리했던 eventId
        if (prepared.alreadyProcessed()) {
            long deliveryCount = transactionService.countDeliveries(prepared.notificationId());
            return new SendNotificationResponse(
                    prepared.notificationId(),
                    prepared.status(),
                    deliveryCount
            );
        }

        // Provider 호출 (DB 트랜잭션을 사용하지 않음)
        List<DeliveryResult> results = sendDeliveries(prepared.deliveries());

        // DB 작업
        transactionService.complete(prepared.notificationId(), results);

        boolean allSucceeded = results.stream().allMatch(DeliveryResult::success);

        return new SendNotificationResponse(
                prepared.notificationId(),
                allSucceeded
                ? NotificationStatus.COMPLETED
                : NotificationStatus.FAILED,
                prepared.deliveryCount()
        );

    }

    private List<DeliveryResult> sendDeliveries(List<DeliveryCommand> deliveries) {

        List<DeliveryResult> results = new ArrayList<>();

        for (DeliveryCommand delivery : deliveries) {
            boolean success;

            try {
                ProviderResult result =
                        switch (delivery.channel()) {
                            case PUSH -> pushProvider.send(delivery.destination());
                            case SMS -> smsProvider.send(delivery.destination());
                            case EMAIL -> emailProvider.send(delivery.destination());
                        };
                success = result.success();
            } catch (RuntimeException e) {
                success = false;
            }

            results.add(new DeliveryResult(delivery.deliveryId(), success));
        }

        return results;
    }

}
