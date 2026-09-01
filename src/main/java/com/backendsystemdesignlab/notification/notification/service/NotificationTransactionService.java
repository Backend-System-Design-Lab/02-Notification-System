package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.dto.DeliveryCommand;
import com.backendsystemdesignlab.notification.notification.dto.DeliveryResult;
import com.backendsystemdesignlab.notification.notification.dto.PreparedNotification;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationRequest;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationTransactionService {

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;

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
    public void recordDeliveryResult(Long notificationId, Long deliveryId, boolean success) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (delivery.getStatus() == DeliveryStatus.SENT || delivery.getStatus() == DeliveryStatus.FAILED) {
            return;
        }

        delivery.recordAttempt();

        if (success) {
            delivery.markSent();
        } else {
            delivery.markFailed();
        }

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

    @Transactional
    public void complete(Long notificationId, List<DeliveryResult> results) {

        // 기존 notification 객체를 쓰지 않는 이유는 첫 번째 Transaction이 끝났기 때문에 두 번째 Transaction에서는 새 영속성 컨텍스트에서 다시 조회
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        List<Long> deliveryIds = results.stream().map(DeliveryResult::deliveryId).toList();

        Map<Long, NotificationDelivery> deliveryMap = deliveryRepository.findAllById(deliveryIds)
                .stream()
                .collect(Collectors.toMap(NotificationDelivery::getId, Function.identity()));

        for (DeliveryResult result : results) {
            NotificationDelivery delivery = deliveryMap.get(result.deliveryId());

            if (delivery == null) {
                throw new IllegalStateException("전송 정보를 찾을 수 없습니다. id=" + result.deliveryId());
            }

            delivery.recordAttempt();

            if (result.success()) {
                delivery.markSent();
            } else {
                delivery.markFailed();
            }
        }

        boolean allSucceeded = results.stream().allMatch(DeliveryResult::success);

        if (allSucceeded) {
            notification.complete();
        } else {
            notification.fail();
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
}
