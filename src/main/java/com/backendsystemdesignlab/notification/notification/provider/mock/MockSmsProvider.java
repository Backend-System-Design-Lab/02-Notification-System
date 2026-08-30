package com.backendsystemdesignlab.notification.notification.provider.mock;

import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.SmsProvider;
import org.springframework.stereotype.Component;

@Component
public class MockSmsProvider implements SmsProvider {

    @Override
    public ProviderResult send(String deviceToken) {
        simulateDelay();
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
