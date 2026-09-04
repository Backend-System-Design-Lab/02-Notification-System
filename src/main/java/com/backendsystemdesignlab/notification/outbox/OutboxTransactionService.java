package com.backendsystemdesignlab.notification.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxTransactionService {

    private final OutboxEventRepository outboxEventRepository;

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
}
