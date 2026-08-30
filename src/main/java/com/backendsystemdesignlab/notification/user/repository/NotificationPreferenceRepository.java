package com.backendsystemdesignlab.notification.user.repository;

import com.backendsystemdesignlab.notification.user.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {
    List<NotificationPreference> findAllByUserIdAndEnabledTrue(Long userId);
}
