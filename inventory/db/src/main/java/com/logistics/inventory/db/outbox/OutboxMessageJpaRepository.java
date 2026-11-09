package com.logistics.inventory.db.outbox;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessage, Long> {
}
