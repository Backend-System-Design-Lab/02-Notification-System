package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.dto.DeliveryCommand;
import com.backendsystemdesignlab.notification.notification.dto.PreparedNotification;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationRequest;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import com.backendsystemdesignlab.notification.notification.repository.NotificationRepository;
import com.backendsystemdesignlab.notification.outbox.OutboxEvent;
import com.backendsystemdesignlab.notification.outbox.OutboxEventRepository;
import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import com.backendsystemdesignlab.notification.user.domain.NotificationPreference;
import com.backendsystemdesignlab.notification.user.domain.User;
import com.backendsystemdesignlab.notification.user.domain.UserDevice;
import com.backendsystemdesignlab.notification.user.repository.NotificationPreferenceRepository;
import com.backendsystemdesignlab.notification.user.repository.UserDeviceRepository;
import com.backendsystemdesignlab.notification.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationTransactionService {

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public PreparedNotification prepare(SendNotificationRequest request) {

        var existing = notificationRepository.findByEventId(request.eventId());

        if (existing.isPresent()) {
            Notification notification = existing.get();

            return new PreparedNotification(
                    notification.getId(),
                    notification.getStatus(),
                    List.of(),
                    true
            );
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Set<NotificationChannel> enabledChannels = preferenceRepository.findAllByUserIdAndEnabledTrue(user.getId())
                .stream()
                .map(NotificationPreference::getChannel)
                .collect(Collectors.toSet());

        Notification notification = notificationRepository.save(new Notification(request.eventId(), user));

        List<NotificationDelivery> deliveries = new ArrayList<>();

        for (NotificationChannel channel : request.channels()) {

            if (!enabledChannels.contains(channel)) {
                continue;
            }

            switch (channel) {
                case PUSH -> createPushDeliveries(
                        user,
                        notification,
                        deliveries
                );

                case SMS -> createSmsDelivery(
                        user,
                        notification,
                        deliveries
                );

                case EMAIL -> createEmailDelivery(
                        user,
                        notification,
                        deliveries
                );
            }
        }

        deliveryRepository.saveAll(deliveries);

        deliveryRepository.flush(); // DB의 ID를 얻기 위함 (delivery.getId())

        for (NotificationDelivery delivery : deliveries) {

            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID().toString(),
                    notification.getId(),
                    delivery.getId(),
                    delivery.getChannel(),
                    delivery.getDestination()
            );

            outboxEventRepository.save(outboxEvent);
        }

        notification.startProcessing();

        List<DeliveryCommand> commands = deliveries.stream()
                .map(delivery -> new DeliveryCommand(delivery.getId(), delivery.getChannel(), delivery.getDestination())).toList();

        return new PreparedNotification(
                notification.getId(),
                notification.getStatus(),
                commands,
                false
        );
    }

    @Transactional
    public void recordRetryFailure(Long deliveryId) {

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            return;
        }

        delivery.recordAttempt();
    }

    @Transactional
    public void recordSuccess(Long notificationId, Long deliveryId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            return;
        }

        delivery.recordAttempt();
        delivery.markSent();

        updateNotificationStatus(notificationId, notification);
    }

    @Transactional
    public void recordFinalFailure(Long notificationId, Long deliveryId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            return;
        }

        delivery.recordAttempt();
        delivery.markFailed();

        updateNotificationStatus(notificationId, notification);
    }

    private void updateNotificationStatus(Long notificationId, Notification notification) {
        deliveryRepository.flush();

        long total = deliveryRepository.countByNotificationId(notificationId);
        long sent = deliveryRepository.countByNotificationIdAndStatus(notificationId, DeliveryStatus.SENT);
        long failed = deliveryRepository.countByNotificationIdAndStatus(notificationId, DeliveryStatus.FAILED);

        // 아직 처리 중인 Delivery 존재
        if (sent + failed < total) {
            return;
        }

        if (failed > 0) {
            notification.fail();
        } else {
            notification.complete();
        }
    }

    @Transactional(readOnly = true)
    public long countDeliveries(Long notificationId) {
        return deliveryRepository.countByNotificationId(notificationId);
    }

    private void createPushDeliveries(User user, Notification notification, List<NotificationDelivery> deliveries) {
        List<UserDevice> devices = userDeviceRepository.findAllByUserIdAndActiveTrue(user.getId());

        for (UserDevice device : devices) {
            deliveries.add(
                    new NotificationDelivery(
                            notification,
                            NotificationChannel.PUSH,
                            device.getDeviceToken()
                    )
            );
        }
    }

    private void createSmsDelivery(User user, Notification notification, List<NotificationDelivery> deliveries) {
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) return;

        deliveries.add(
                new NotificationDelivery(
                        notification,
                        NotificationChannel.SMS,
                        user.getPhoneNumber()
                )
        );
    }

    private void createEmailDelivery(User user, Notification notification, List<NotificationDelivery> deliveries) {
        if (user.getEmail() == null || user.getEmail().isBlank()) return;

        deliveries.add(
                new NotificationDelivery(
                        notification,
                        NotificationChannel.EMAIL,
                        user.getEmail()
                )
        );
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));

        return delivery.getStatus() == DeliveryStatus.SENT || delivery.getStatus() == DeliveryStatus.FAILED;
    }

    @Transactional
    public void recordPublishFinalFailure(Long notificationId, Long deliveryId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            return;
        }

        delivery.markFailed();

        updateNotificationStatus(notificationId, notification);
    }
}
