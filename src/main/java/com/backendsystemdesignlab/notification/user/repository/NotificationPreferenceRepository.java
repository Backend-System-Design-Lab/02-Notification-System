package com.backendsystemdesignlab.notification.user.repository;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import com.backendsystemdesignlab.notification.user.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
    List<NotificationPreference> findAllByUserIdAndEnabledTrue(Long userId);
    Optional<NotificationPreference> findByUserIdAndChannel(Long userId, NotificationChannel channel);
}
