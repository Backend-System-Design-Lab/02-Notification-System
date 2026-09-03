package com.backendsystemdesignlab.notification.ratelimit;

import com.backendsystemdesignlab.notification.user.domain.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class NotificationRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    private final int pushLimit;
    private final int smsLimit;
    private final int emailLimit;

    public NotificationRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${notification.rate-limit.push:100}") int pushLimit,
            @Value("${notification.rate-limit.sms:20}") int smsLimit,
            @Value("${notification.rate-limit.email:50}") int emailLimit
    ) {
        this.redisTemplate = redisTemplate;
        this.pushLimit = pushLimit;
        this.smsLimit = smsLimit;
        this.emailLimit = emailLimit;

        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/rate-limit.lua"));
        this.script.setResultType(Long.class);
    }

    public boolean tryAcquire(NotificationChannel channel) {

        long second = Instant.now().getEpochSecond();

        String key = "notification:rate-limit:" + channel.name() + ":" + second;

        try {
            Long result = redisTemplate.execute(
                    script,
                    List.of(key),                           // KEYS[1]
                    String.valueOf(limit(channel)),  // ARGV[1]
                    "2"                                     // ARGV[2]
            );

            return result != null && result == 1L;
        } catch (RedisConnectionFailureException e) {
            log.warn("[RateLimit] Redis 사용 불가. Fail-open. channel={}", channel);
            return true;
        }
    }

    private int limit(NotificationChannel channel) {
        return switch (channel) {
            case PUSH -> pushLimit;
            case SMS -> smsLimit;
            case EMAIL -> emailLimit;
        };
    }
}
