package com.backendsystemdesignlab.notification.messaging;

import com.backendsystemdesignlab.notification.notification.domain.*;
import com.backendsystemdesignlab.notification.notification.repository.*;
import com.backendsystemdesignlab.notification.notification.service.NotificationTransactionService;
import com.backendsystemdesignlab.notification.user.domain.*;
import com.backendsystemdesignlab.notification.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "notification.consumer.enabled=false",
        "outbox.publisher.enabled=false"
})
class DeliveryClaimIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationDeliveryRepository deliveryRepository;
    @Autowired NotificationTransactionService transactionService;

    @Test
    void onlyOneConsumerCanClaimSameDelivery() throws Exception {
        User user = userRepository.save(
                new User("claim-" + UUID.randomUUID() + "@test.local", null)
        );

        Notification notification = notificationRepository.save(
                new Notification("claim-" + UUID.randomUUID(), user)
        );

        NotificationDelivery delivery = deliveryRepository.save(
                new NotificationDelivery(
                        notification,
                        NotificationChannel.PUSH,
                        "test-token"
                )
        );

        Long deliveryId = delivery.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Optional<NotificationTransactionService.DeliveryClaim>> task = () -> {
                ready.countDown();

                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start timeout");
                }

                return transactionService.tryClaim(deliveryId, 1);
            };

            Future<Optional<NotificationTransactionService.DeliveryClaim>> first = executor.submit(task);
            Future<Optional<NotificationTransactionService.DeliveryClaim>> second = executor.submit(task);

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Optional<NotificationTransactionService.DeliveryClaim> a = first.get(10, TimeUnit.SECONDS);
            Optional<NotificationTransactionService.DeliveryClaim> b = second.get(10, TimeUnit.SECONDS);

            assertEquals(
                    1,
                    (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)
            );

            NotificationDelivery saved =
                    deliveryRepository.findById(deliveryId).orElseThrow();

            var winner = a.orElseGet(() -> b.orElseThrow());

            assertEquals(1, winner.attempt());
            assertEquals(winner.token(), saved.getProcessingToken());
            assertEquals(1, saved.getAttemptCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            deliveryRepository.deleteById(deliveryId);
            notificationRepository.deleteById(notification.getId());
            userRepository.deleteById(user.getId());
        }
    }
}
