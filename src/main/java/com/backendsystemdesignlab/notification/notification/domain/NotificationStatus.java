package com.backendsystemdesignlab.notification.notification.domain;

public enum NotificationStatus {
    PENDING,    // 알림 생성
    PROCESSING, // 실제 채널 전송 중
    COMPLETED,  // 모든 필요한 전송 처리 완료
    FAILED      // 알림 처리 실패
}
