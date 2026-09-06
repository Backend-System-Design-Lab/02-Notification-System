package com.backendsystemdesignlab.notification.preference;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceCache {

    private static final String KEY_PREFIX = "notification:preference:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Set<NotificationChannel>> find(Long userId) {

        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);

            if (value == null) {
                log.debug("[Preference Cache MISS] userId={}", userId);
                return Optional.empty();
            }

            PreferenceCacheValue cached =  objectMapper.readValue(value, PreferenceCacheValue.class);
            log.debug("[Preference Cache HIT] userId={}", userId);
            return Optional.of(cached.enabledChannels());
        } catch (DataAccessException e) {
            log.warn("[Preference Cache ERROR] Redis 조회 실패. DB Fallback. userId={}", userId);
            return Optional.empty();
        } catch (JacksonException e) {
            log.warn("[Preference Cache ERROR] 역직렬화 실패. DB Fallback. userId={}", userId);
            return Optional.empty();
        }
    }

    public void save(Long userId, Set<NotificationChannel> enabledChannels) {

        try {
            PreferenceCacheValue value = new PreferenceCacheValue(enabledChannels);
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, json, TTL);
        } catch (DataAccessException e) {
            log.warn("[Preference Cache ERROR] Redis 저장 실패. userId={}", userId);
        } catch (JacksonException e) {
            log.warn("[Preference Cache ERROR] 직렬화 실패. userId={}", userId);
        }
    }

    public void evict(Long userId) {

        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (DataAccessException e) {
            log.warn("[Preference Cache] Redis 삭제 실패. userId={}", userId);
        }
    }
}
