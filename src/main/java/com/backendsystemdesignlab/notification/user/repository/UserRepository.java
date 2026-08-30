package com.backendsystemdesignlab.notification.user.repository;

import com.backendsystemdesignlab.notification.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
