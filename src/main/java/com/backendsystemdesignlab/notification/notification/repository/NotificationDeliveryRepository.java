package com.backendsystemdesignlab.notification.notification.repository;

import com.backendsystemdesignlab.notification.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    long countByNotificationId(Long notificationId);
}
