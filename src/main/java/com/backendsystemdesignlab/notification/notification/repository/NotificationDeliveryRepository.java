package com.backendsystemdesignlab.notification.notification.repository;

import com.backendsystemdesignlab.notification.notification.domain.DeliveryStatus;
import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    @Modifying
    @Query("""
        UPDATE NotificationDelivery d
           SET d.status = :processing,
               d.processingStartedAt = :now,
               d.processingToken = :token,
               d.attemptCount = d.attemptCount + 1
         WHERE d.id = :deliveryId
           AND d.status = :pending
           AND d.attemptCount = :expectedPreviousCount
           AND d.attemptCount < :maxAttempts
        """)
    int claimPending(
            @Param("deliveryId") Long deliveryId,
            @Param("pending") DeliveryStatus pending,
            @Param("processing") DeliveryStatus processing,
            @Param("now") LocalDateTime now,
            @Param("token") String token,
            @Param("expectedPreviousCount") int expectedPreviousCount,
            @Param("maxAttempts") int maxAttempts
            );

    long countByNotificationId(Long notificationId);

    long countByNotificationIdAndStatus(Long notificationId, DeliveryStatus status);

    List<NotificationDelivery>
    findTop100ByStatusAndProcessingStartedAtBeforeOrderByProcessingStartedAtAsc(
            DeliveryStatus status,
            LocalDateTime cutoff
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM NotificationDelivery d WHERE d.id = :id")
    Optional<NotificationDelivery> findByIdForUpdate(@Param("id") Long id);
}
