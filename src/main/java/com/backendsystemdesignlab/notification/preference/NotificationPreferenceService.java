package com.backendsystemdesignlab.notification.preference;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import com.backendsystemdesignlab.notification.user.domain.NotificationPreference;
import com.backendsystemdesignlab.notification.user.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationPreferenceCache preferenceCache;
    private final NotificationPreferenceTransactionService transactionService;

    public Set<NotificationChannel> getEnabledChannels(Long userId) {
        var cached = preferenceCache.find(userId);

        if (cached.isPresent()) {
            return cached.get();
        }

        // DB Fallback
        Set<NotificationChannel> enabledChannels = preferenceRepository.findAllByUserIdAndEnabledTrue(userId)
                .stream()
                .map(NotificationPreference::getChannel)
                .collect(Collectors.toSet());

        preferenceCache.save(userId, enabledChannels);
        return enabledChannels;
    }

    public void updatePreference(Long userId, NotificationChannel channel, boolean enabled) {
        transactionService.update(userId, channel, enabled);
        preferenceCache.evict(userId);
    }
}
