package com.backendsystemdesignlab.notification.dedup;

import com.backendsystemdesignlab.notification.notification.dto.SendNotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDedupCache {

    private static final String KEY_PREFIX = "notification:dedup:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<SendNotificationResponse> find(String eventId) {

        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + eventId);

            if (value == null) {
                log.debug("[Dedup MISS] eventId={}", eventId);
                return Optional.empty();
            }

            log.debug("[Dedup HIT] eventId={}", eventId);

            return Optional.of(
                    objectMapper.readValue(
                            value,
                            SendNotificationResponse.class
                    )
            );
        } catch (DataAccessException e) {
            log.warn("[Dedup] Redis 조회 실패. DB로 fallback. eventId={}", eventId);
            return Optional.empty();
        } catch (JacksonException e) {
            log.warn("[Dedup] Redis 데이터 역직렬화 실패. eventId={}", eventId);
            return Optional.empty();
        }
    }

    public void save(String eventId, SendNotificationResponse response) {

        try {
            String value = objectMapper.writeValueAsString(response);
            log.debug("[Dedup SAVE] eventId={}", eventId);
            redisTemplate.opsForValue().set(KEY_PREFIX + eventId, value, TTL);
        } catch (DataAccessException e) {
            log.warn("[Dedup] Redis 저장 실패. 캐시 없이 계속 진행. eventId={}", eventId);
        } catch (JacksonException e) {
            log.warn("[Dedup] 응답 직렬화 실패. eventId={}", eventId);
        }
    }
}
