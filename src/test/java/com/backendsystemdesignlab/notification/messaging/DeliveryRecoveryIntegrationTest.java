package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.domain.*;
import com.backendsystemdesignlab.notification.notification.repository.*;
import com.backendsystemdesignlab.notification.notification.service.*;
import com.backendsystemdesignlab.notification.user.domain.*;
import com.backendsystemdesignlab.notification.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "notification.consumer.enabled=false",
        "outbox.publisher.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class DeliveryRecoveryIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationDeliveryRepository deliveryRepository;
    @Autowired NotificationTransactionService transactionService;
    @Autowired DeliveryRecoveryTransactionService recoveryService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void expiredDeliveryShouldRecoverWithOneOutboxEvent() {
        User user = userRepository.save(
                new User("recovery-" + UUID.randomUUID() + "@test.local", null)
        );

        Notification notification = notificationRepository.save(
                new Notification("recovery-" + UUID.randomUUID(), user)
        );

        NotificationDelivery delivery = deliveryRepository.save(
                new NotificationDelivery(
                        notification,
                        NotificationChannel.PUSH,
                        "test-token"
                )
        );

        Long id = delivery.getId();

        try {
            // 최초 선점
            assertTrue(transactionService.tryClaim(id, 1).isPresent());

            // Worker가 오래전에 중단된 상황을 모의
            jdbcTemplate.update(
                    """
                    UPDATE notification_deliveries
                    SET processing_started_at = ?
                    WHERE id = ?
                    """,
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                    id
            );

            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

            // 첫 번째 복구 성공
            assertTrue(recoveryService.recoverIfExpired(id, cutoff));

            // 두 번째 복구는 거부
            assertFalse(recoveryService.recoverIfExpired(id, cutoff));

            NotificationDelivery recovered =
                    deliveryRepository.findById(id).orElseThrow();

            assertEquals(DeliveryStatus.PENDING, recovered.getStatus());
            assertNull(recovered.getProcessingToken());
            assertNull(recovered.getProcessingStartedAt());

            Integer outboxCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    id
            );

            assertEquals(1, outboxCount);

            Integer deliveryAttempt = jdbcTemplate.queryForObject(
                    "SELECT delivery_attempt FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    id
            );

            assertEquals(2, deliveryAttempt);

        } finally {
            jdbcTemplate.update(
                    "DELETE FROM outbox_events WHERE delivery_id = ?", id
            );
            deliveryRepository.deleteById(id);
            notificationRepository.deleteById(notification.getId());
            userRepository.deleteById(user.getId());
        }
    }

    @Test
    void expiredDeliveryShouldFailAfterThreeAttempts() {

        User user = userRepository.save(
                new User("failed-" + UUID.randomUUID() + "@test.local", null)
        );

        Notification notification = notificationRepository.save(
                new Notification("failed-" + UUID.randomUUID(), user)
        );

        NotificationDelivery delivery = deliveryRepository.save(
                new NotificationDelivery(
                        notification,
                        NotificationChannel.PUSH,
                        "test-token"
                )
        );

        Long id = delivery.getId();

        try {
            // 첫 번째 선점
            String token1 = transactionService.tryClaim(id, 1)
                    .orElseThrow()
                    .token();

            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(),
                    id,
                    token1
            );

            // 오래된 첫 번째 메시지는 다시 선점할 수 없음
            assertTrue(transactionService.tryClaim(id, 1).isEmpty());

            // 두 번째 선점
            String token2 = transactionService.tryClaim(id, 2)
                    .orElseThrow()
                    .token();

            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(),
                    id,
                    token2
            );

            // 세 번째 선점
            String token3 = transactionService.tryClaim(id, 3)
                    .orElseThrow()
                    .token();

            // 처리 시작 시각을 10분 전으로 변경
            jdbcTemplate.update(
                    """
                    UPDATE notification_deliveries
                       SET processing_started_at = ?
                     WHERE id = ?
                    """,
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                    id
            );

            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

            assertTrue(recoveryService.recoverIfExpired(id, cutoff));

            NotificationDelivery recovered =
                    deliveryRepository.findById(id).orElseThrow();

            assertEquals(DeliveryStatus.FAILED, recovered.getStatus());
            assertEquals(3, recovered.getAttemptCount());
            assertNull(recovered.getProcessingToken());

            // Notification 전체도 FAILED
            assertEquals(
                    NotificationStatus.FAILED,
                    notificationRepository.findById(notification.getId())
                            .orElseThrow()
                            .getStatus()
            );

            // 4번째 Outbox는 생성되지 않아야 함
            Integer outboxCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    id
            );

            assertEquals(3, outboxCount);

            // 이미 FAILED이므로 다시 복구할 수 없음
            assertFalse(recoveryService.recoverIfExpired(id, cutoff));

        } finally {
            jdbcTemplate.update(
                    "DELETE FROM outbox_events WHERE delivery_id = ?",
                    id
            );

            deliveryRepository.deleteById(id);
            notificationRepository.deleteById(notification.getId());
            userRepository.deleteById(user.getId());
        }
    }
}
