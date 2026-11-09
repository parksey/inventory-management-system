package com.logistics.inventory.db.outbox;

import com.logistics.inventory.domain.outbox.entity.OutboxMessage;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessage, Long> {

    // 아무도 선점하지 않았거나 선점이 만료된 PENDING
    @Query(value = """
            SELECT id
            FROM outbox_message
            WHERE status = 'PENDING'
              AND available_at <= now()
              AND (locked_at IS NULL OR locked_at < now() - make_interval(secs => :lockSeconds))
              AND aggregate_type = 'STOCK_TRANSACTION'
              AND event_type = 'STOCK_CHANGED'
            ORDER BY available_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsForUpdate(@Param("limit") int limit, @Param("lockSeconds") long lockSeconds);

    // 선점
    @Modifying
    @Query(value = """
            UPDATE outbox_message
            SET locked_by = :workerId, locked_at = now()
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markClaimed(@Param("ids") List<Long> ids, @Param("workerId") String workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessage m where m.id = :id")
    Optional<OutboxMessage> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query(value = """
            UPDATE outbox_message
            SET available_at = now() + make_interval(secs => :seconds)
            WHERE id = :id
            """, nativeQuery = true)
    int delay(@Param("id") Long id, @Param("seconds") double seconds);
}
