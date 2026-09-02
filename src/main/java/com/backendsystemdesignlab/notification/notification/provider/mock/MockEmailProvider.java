package com.backendsystemdesignlab.notification.notification.provider.mock;

import com.backendsystemdesignlab.notification.notification.provider.EmailProvider;
import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MockEmailProvider implements EmailProvider {

    private final boolean forceFailure;
    private final int failFirstAttempts;

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public MockEmailProvider(
            @Value("${mock.provider.force-failure:false}") boolean forceFailure,
            @Value("${mock.provider.fail-first-attempts:0}") int failFirstAttempts) {
        this.forceFailure = forceFailure;
        this.failFirstAttempts = failFirstAttempts;
    }

    @Override
    public ProviderResult send(String email) {
        simulateDelay();
        if (forceFailure) return new ProviderResult(false);

        int currentAttempt = attempts.computeIfAbsent(email, key -> new AtomicInteger()).incrementAndGet(); // 증가 연산을 원자적 처리

        if (currentAttempt <= failFirstAttempts) {
            return new ProviderResult(false);
        }

        return new ProviderResult(true);
    }

    private void simulateDelay() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
