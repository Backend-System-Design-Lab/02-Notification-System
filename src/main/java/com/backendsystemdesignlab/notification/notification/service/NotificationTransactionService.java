package com.backendsystemdesignlab.notification.notification.service;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.Notification;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import com.backendsystemdesignlab.notification.notification.dto.DeliveryCommand;
import com.backendsystemdesignlab.notification.notification.dto.PreparedNotification;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationRequest;
import com.backendsystemdesignlab.notification.notification.dto.SendNotificationResponse;
import com.backendsystemdesignlab.notification.notification.repository.NotificationDeliveryRepository;
import com.backendsystemdesignlab.notification.notification.repository.NotificationRepository;
import com.backendsystemdesignlab.notification.outbox.OutboxEvent;
import com.backendsystemdesignlab.notification.outbox.OutboxEventRepository;
import com.backendsystemdesignlab.notification.preference.NotificationPreferenceService;
import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import com.backendsystemdesignlab.notification.user.domain.User;
import com.backendsystemdesignlab.notification.user.domain.UserDevice;
import com.backendsystemdesignlab.notification.user.repository.UserDeviceRepository;
import com.backendsystemdesignlab.notification.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.backendsystemdesignlab.notification.outbox.OutboxRoute;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NotificationTransactionService {

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final NotificationPreferenceService preferenceService;
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

        Set<NotificationChannel> enabledChannels = preferenceService.getEnabledChannels(user.getId());

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

    private static final int MAX_ATTMPTS = 3;

    @Transactional
    public Optional<DeliveryClaim> tryClaim(Long deliveryId, int messageAttempt) {
        if (messageAttempt < 1 || messageAttempt > MAX_ATTMPTS) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();

        int updated = deliveryRepository.claimPending(
                deliveryId,
                DeliveryStatus.PENDING,
                DeliveryStatus.PROCESSING,
                LocalDateTime.now(),
                token,
                messageAttempt - 1,
                MAX_ATTMPTS
        );

        if (updated == 0) {
            return Optional.empty();
        }

        int attempt = deliveryRepository.findById(deliveryId)
                .orElseThrow()
                .getAttemptCount();

        return Optional.of(new DeliveryClaim(token, attempt));
    }

    public final int MAX_ATTEMPTS = 3;

    @Transactional
    public void recordRetryFailureAndEnqueue(
            Long notificationId,
            Long deliveryId,
            String token
    ) {
        // 1. Notification 먼저 잠금
        notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Notification not found")
                );

        // 2. Delivery 잠금
        NotificationDelivery delivery = deliveryRepository
                .findByIdForUpdate(deliveryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Delivery not found")
                );

        // 3. 선점 토큰 검증
        if (!delivery.isClaimedBy(token)) {
            return;
        }

        // 4. 재시도 횟수 확인
        if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new IllegalStateException("Retry attempts exhausted");
        }

        // 5. 다음 발송 회차
        int nextAttempt = delivery.getAttemptCount() + 1;

        // 6. 상태 복구
        delivery.returnToPending();

        // 7. Retry용 Outbox 저장
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                notificationId,
                deliveryId,
                delivery.getChannel(),
                delivery.getDestination(),
                nextAttempt,
                OutboxRoute.RETRY
        );

        outboxEventRepository.save(event);
    }

    @Transactional
    public void recordSuccess(Long notificationId, Long deliveryId, String token) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        NotificationDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("전송 정보를 찾을 수 없습니다."));

        if (!delivery.isClaimedBy(token)) {
            return;
        }

        delivery.markSent();

        updateNotificationStatus(notificationId, notification);
    }

    @Transactional
    public void recordFinalFailureAndEnqueue(
            Long notificationId,
            Long deliveryId,
            String token
    ) {
        // 1. Notification 먼저 잠금
        Notification notification = notificationRepository
                .findByIdForUpdate(notificationId)
                .orElseThrow();

        // 2. Delivery 잠금
        NotificationDelivery delivery = deliveryRepository
                .findByIdForUpdate(deliveryId)
                .orElseThrow();

        // 3. 실제 선점자 확인
        if (!delivery.isClaimedBy(token)) {
            return;
        }

        // 4. 최종 시도인지 검증
        if (delivery.getAttemptCount() != MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "Final failure before max attempts"
            );
        }

        // 5. 최종 실패 처리
        delivery.markFailed();

        // 6. Notification 전체 상태 갱신
        updateNotificationStatus(notificationId, notification);

        // 7. DLQ 발행을 위한 Outbox 저장
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                notificationId,
                deliveryId,
                delivery.getChannel(),
                delivery.getDestination(),
                delivery.getAttemptCount(),
                OutboxRoute.DLQ
        );

        outboxEventRepository.save(event);
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

    @Transactional(readOnly = true)
    public Optional<SendNotificationResponse> findExisingResponse(String eventId) {
        return notificationRepository.findByEventId(eventId)
                .map(notification -> {
                    long deliveryCount = deliveryRepository.countByNotificationId(notification.getId());
                    return new SendNotificationResponse(
                            notification.getId(),
                            notification.getStatus(),
                            deliveryCount
                    );
                });
    }

    public record DeliveryClaim(
            String token,
            int attempt
    ) {}

}
