package com.backendsystemdesignlab.notification.notification.provider.mock;

import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.PushProvider;
import org.springframework.stereotype.Component;

@Component
public class MockPushProvider implements PushProvider {

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
