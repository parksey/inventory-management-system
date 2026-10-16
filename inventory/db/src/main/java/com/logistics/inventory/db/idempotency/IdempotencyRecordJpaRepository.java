package com.logistics.inventory.db.idempotency;

import com.logistics.inventory.domain.idempotency.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecord, String> {

    // 앞에 SELECT 없이 바로 넣는다. 같은 키가 이미 있으면(다른 트랜잭션이 넣는 중이면 끝날 때까지 기다린 뒤) 오류 없이 넣지 않는다. 넣은 행 수(0 또는 1)를 돌려준다
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_record (idempotency_key, type, product_code, quantity, requester_id)
            VALUES (:idempotencyKey, :type, :productCode, :quantity, :requesterId)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("type") String type,
            @Param("productCode") String productCode,
            @Param("quantity") long quantity,
            @Param("requesterId") String requesterId
    );
}
