package com.backendsystemdesignlab.notification.notification.provider;

public interface PushProvider {
    ProviderResult send(String deviceToken);
}
