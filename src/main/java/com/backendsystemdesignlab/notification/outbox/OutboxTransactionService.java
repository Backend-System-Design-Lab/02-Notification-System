package com.backendsystemdesignlab.notification.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxTransactionService {

    private final OutboxEventRepository outboxEventRepository;
    private static final int MAX_PUBLISH_ATTEMPTS = 5;

    @Transactional(readOnly = true)
    public List<OutboxEvent> findPendingEvents() {
        return outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
    }

    @Transactional
    public void markPublished(Long outboxEventId) {
        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + outboxEventId));

        if (event.getStatus() == OutboxStatus.PUBLISHED) {
            return;
        }
        event.markPublished();
    }

    @Transactional
    public boolean recordPublishFailure(Long outboxEventId, String error) {
        OutboxEvent event = outboxEventRepository.findById(outboxEventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + outboxEventId));

        if (event.getStatus() != OutboxStatus.PENDING) {
            return false;
        }

        event.recordFailure(error);

        if (event.getAttemptCount() >= MAX_PUBLISH_ATTEMPTS) {
            event.markFailed();
            return true;
        }

        return false;
    }
}
