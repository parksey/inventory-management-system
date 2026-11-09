package com.logistics.inventory.domain.outbox;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRelayer {

    private final OutboxRelayService outboxRelayService;

    public void relayDue(final String workerId) {
        final List<Long> ids = outboxRelayService.claimDue(workerId);
        for (final Long id : ids) {
            try {
                outboxRelayService.relay(id, workerId);
            } catch (final RuntimeException e) {
                outboxRelayService.recordFailure(id, workerId, e.getMessage());
            }
        }
    }
}
