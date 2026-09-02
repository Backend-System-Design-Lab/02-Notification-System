package com.backendsystemdesignlab.notification.notification.provider;

public interface EmailProvider {
    ProviderResult send(String email, String idempotencyKey);
}
