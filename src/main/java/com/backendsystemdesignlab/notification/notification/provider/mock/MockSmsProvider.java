package com.backendsystemdesignlab.notification.notification.provider.mock;

import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockSmsProvider implements SmsProvider {

    private final boolean forceFailure;
    private final Set<String> processedKeys =
            ConcurrentHashMap.newKeySet();

    public MockSmsProvider(@Value("${mock.provider.force-failure:false}") boolean forceFailure) {
        this.forceFailure = forceFailure;
    }

    @Override
    public ProviderResult send(String phoneNumber, String idempotencyKey) {
        simulateDelay();
        if (processedKeys.contains(idempotencyKey)) {
            return new ProviderResult(true);
        }
        if (forceFailure) return new ProviderResult(false);

        processedKeys.add(idempotencyKey);
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
