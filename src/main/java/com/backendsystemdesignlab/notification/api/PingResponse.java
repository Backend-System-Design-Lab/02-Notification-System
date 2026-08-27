package com.backendsystemdesignlab.notification.api;

public record PingResponse(String message) {

    public static PingResponse pong() {
        return new PingResponse("pong");
    }
}
