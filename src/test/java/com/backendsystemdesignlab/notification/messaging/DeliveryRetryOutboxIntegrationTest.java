package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.domain.*;
import com.backendsystemdesignlab.notification.notification.repository.*;
import com.backendsystemdesignlab.notification.notification.service.*;
import com.backendsystemdesignlab.notification.outbox.OutboxRoute;
import com.backendsystemdesignlab.notification.user.domain.*;
import com.backendsystemdesignlab.notification.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "notification.consumer.enabled=false",
        "outbox.publisher.enabled=false"
})
class DeliveryRetryOutboxIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationDeliveryRepository deliveryRepository;
    @Autowired NotificationTransactionService transactionService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void failedDeliveryShouldCreateOneRetryOutbox() {

        User user = userRepository.save(
                new User("retry-" + UUID.randomUUID() + "@test.local", null)
        );

        Notification notification = notificationRepository.save(
                new Notification("retry-" + UUID.randomUUID(), user)
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
            String token = transactionService.tryClaim(id, 1)
                    .orElseThrow()
                    .token();

            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(),
                    id,
                    token
            );

            NotificationDelivery updated =
                    deliveryRepository.findById(id).orElseThrow();

            assertEquals(DeliveryStatus.PENDING, updated.getStatus());
            assertEquals(1, updated.getAttemptCount());
            assertNull(updated.getProcessingToken());

            // 동일 토큰으로 중복 처리해도 Outbox 추가 생성 방지
            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(),
                    id,
                    token
            );

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    id
            );

            assertEquals(1, count);

            Integer nextAttempt = jdbcTemplate.queryForObject(
                    "SELECT delivery_attempt FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    id
            );

            assertEquals(2, nextAttempt);

            String route = jdbcTemplate.queryForObject(
                    "SELECT publish_route FROM outbox_events WHERE delivery_id = ?",
                    String.class,
                    id
            );

            assertEquals(OutboxRoute.RETRY.name(), route);

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

    @Test
    void finalFailureShouldCreateOneDlqOutbox() {

        User user = userRepository.save(
                new User("dlq-" + UUID.randomUUID() + "@test.local", null)
        );

        Notification notification = notificationRepository.save(
                new Notification("dlq-" + UUID.randomUUID(), user)
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
            // 첫 번째 시도 실패
            String token1 = transactionService.tryClaim(id, 1)
                    .orElseThrow().token();

            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(), id, token1
            );

            // 두 번째 시도 실패
            String token2 = transactionService.tryClaim(id, 2)
                    .orElseThrow().token();

            transactionService.recordRetryFailureAndEnqueue(
                    notification.getId(), id, token2
            );

            // 세 번째 시도 최종 실패
            String token3 = transactionService.tryClaim(id, 3)
                    .orElseThrow().token();

            transactionService.recordFinalFailureAndEnqueue(
                    notification.getId(), id, token3
            );

            NotificationDelivery updated =
                    deliveryRepository.findById(id).orElseThrow();

            assertEquals(DeliveryStatus.FAILED, updated.getStatus());
            assertEquals(3, updated.getAttemptCount());
            assertNull(updated.getProcessingToken());

            assertEquals(
                    NotificationStatus.FAILED,
                    notificationRepository.findById(notification.getId())
                            .orElseThrow().getStatus()
            );

            // Retry 2건, DLQ 1건
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class, id
            );

            assertEquals(3, total);

            Integer dlqCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM outbox_events
                    WHERE delivery_id = ?
                      AND publish_route = 'DLQ'
                      AND delivery_attempt = 3
                    """,
                    Integer.class, id
            );

            assertEquals(1, dlqCount);

            // 같은 토큰으로 다시 처리해도 중복 DLQ 생성 방지
            transactionService.recordFinalFailureAndEnqueue(
                    notification.getId(), id, token3
            );

            Integer countAfterDuplicate = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class, id
            );

            assertEquals(3, countAfterDuplicate);

        } finally {
            jdbcTemplate.update(
                    "DELETE FROM outbox_events WHERE delivery_id = ?", id
            );

            deliveryRepository.deleteById(id);
            notificationRepository.deleteById(notification.getId());
            userRepository.deleteById(user.getId());
        }
    }
}