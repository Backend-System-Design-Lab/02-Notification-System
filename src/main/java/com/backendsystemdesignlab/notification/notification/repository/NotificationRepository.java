package com.backendsystemdesignlab.notification.notification.repository;

import com.backendsystemdesignlab.notification.notification.domain.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByEventId(String eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select n
            from Notification n 
            where n.id = :id            
            """)
    Optional<Notification> findByIdForUpdate(@Param("id") Long id);
}
