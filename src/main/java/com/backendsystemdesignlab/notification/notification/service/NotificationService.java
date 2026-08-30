package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationRequest;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationResponse;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import com.backendsystemdesignlab.notification.notification.repository.NotificationRepository;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;

    @Transactional
    public SendNotificationResponse send(SendNotificationRequest request) {

        // 동일 eventId가 이미 처리된 경우 기존 결과 반환
        var existing = notificationRepository.findByEventId(request.eventId());

        if (existing.isPresent()) {
            Notification notification = existing.get();

            return new SendNotificationResponse(
                    notification.getId(),
                    notification.getStatus(),
                    deliveryRepository.countByNotificationId(notification.getId())
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

        return new SendNotificationResponse(
                notification.getId(),
                notification.getStatus(),
                deliveries.size()
        );
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
}
