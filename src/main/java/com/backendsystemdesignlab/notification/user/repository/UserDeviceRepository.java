package com.backendsystemdesignlab.notification.user.repository;

import com.backendsystemdesignlab.notification.user.domain.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
    List<UserDevice> findAllByUserIdAndActiveTrue(Long userId);
}
