package com.backendsystemdesignlab.notification.preference;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import com.backendsystemdesignlab.notification.user.domain.NotificationPreference;
import com.backendsystemdesignlab.notification.user.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceTransactionService {

    private final NotificationPreferenceRepository preferenceRepository;

    @Transactional
    public void update(Long userId, NotificationChannel channel, boolean enabled) {
        NotificationPreference preference = preferenceRepository.findByUserIdAndChannel(userId, channel)
                .orElseThrow(() -> new IllegalArgumentException("알림 설정을 찾을 수 없습니다."));
        preference.changeEnabled(enabled);
    }
}
