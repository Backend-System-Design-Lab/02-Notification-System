package com.backendsystemdesignlab.notification.notification.provider;

public interface SmsProvider {
    ProviderResult send(String phoneNumber);
}
