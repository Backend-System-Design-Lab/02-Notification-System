package com.backendsystemdesignlab.notification.notification.provider.mock;

import com.backendsystemdesignlab.notification.notification.provider.ProviderResult;
import com.backendsystemdesignlab.notification.notification.provider.PushProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockPushProvider implements PushProvider {

    private final boolean forceFailure;

    public MockPushProvider(@Value("${mock.provider.force-failure:false}") boolean forceFailure) {
        this.forceFailure = forceFailure;
    }

    @Override
    public ProviderResult send(String deviceToken) {
        simulateDelay();
        if (forceFailure) return new ProviderResult(false);
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
