package com.logistics.inventory.db.erp;

import com.logistics.inventory.domain.erp.entity.ErpSync;
import com.logistics.inventory.domain.erp.model.ErpDelivery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ErpSyncJpaRepository extends JpaRepository<ErpSync, Long> {

    // 메시지 하나에 erp_sync 하나. 이미 있으면(다른 Pod가 먼저 넘김) 그대로 둔다
    @Modifying
    @Query(value = """
            INSERT INTO erp_sync (outbox_message_id, erp_idempotency_key, next_retry_at)
            VALUES (:outboxMessageId, :idempotencyKey, now())
            ON CONFLICT (outbox_message_id) DO NOTHING
            """, nativeQuery = true)
    int createIfAbsent(@Param("outboxMessageId") Long outboxMessageId, @Param("idempotencyKey") String idempotencyKey);

    @Query(value = """
            SELECT id
            FROM erp_sync
            WHERE status = 'PENDING'
              AND next_retry_at <= now()
              AND locked_at IS NULL
            ORDER BY next_retry_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findDueIdsForUpdate(@Param("limit") int limit);

    @Query(value = """
            SELECT id
            FROM erp_sync
            WHERE status = 'UNKNOWN'
              AND next_retry_at <= now()
              AND (locked_at IS NULL OR locked_at < now() - make_interval(secs => :lockSeconds))
            ORDER BY next_retry_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findCheckDueIdsForUpdate(@Param("limit") int limit, @Param("lockSeconds") long lockSeconds);

    @Modifying
    @Query(value = """
            UPDATE erp_sync
            SET status = 'UNKNOWN', locked_by = NULL, locked_at = NULL, next_retry_at = now(),
                last_error = '선점 만료: 보내던 배치가 결과를 남기지 못함'
            WHERE status = 'PENDING'
              AND locked_at < now() - make_interval(secs => :lockSeconds)
            """, nativeQuery = true)
    int markExpiredClaimsUnknown(@Param("lockSeconds") long lockSeconds);

    // 선점 표시: 위에서 잠근 행에 누가, 언제 잡았는지 남긴다. 시각은 만료 비교와 같은 DB 시계(now())로 넣는다
    @Modifying
    @Query(value = """
            UPDATE erp_sync
            SET locked_by = :workerId, locked_at = now(), attempts = attempts + 1
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markClaimed(@Param("ids") List<Long> ids, @Param("workerId") String workerId);

    // 선점한 건의 보낼 내용: erp_sync의 키 + outbox의 payload를 조인 한 번으로
    @Query("""
            select new com.logistics.inventory.domain.erp.model.ErpDelivery(s.id, s.erpIdempotencyKey, o.payload)
            from ErpSync s
            join OutboxMessage o on o.id = s.outboxMessageId
            where s.id in :ids
            order by s.id
            """)
    List<ErpDelivery> findDeliveries(@Param("ids") List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ErpSync s where s.id = :id")
    Optional<ErpSync> findByIdForUpdate(@Param("id") Long id);

    // 배치가 비교하는 시계(DB의 now())로 다음 시도 시각을 정하려고 읽는다. 트랜잭션 시작 시각이다
    @Query(value = "SELECT now()", nativeQuery = true)
    Instant currentTime();
}
