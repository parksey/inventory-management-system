package com.logistics.inventory.domain.outbox.entity;

import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.StockChange;
import com.logistics.inventory.domain.product.entity.ProductCode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    public static final String STOCK_TRANSACTION = "STOCK_TRANSACTION";
    public static final String STOCK_CHANGED = "STOCK_CHANGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "company_code", nullable = false, updatable = false))
    private CompanyCode companyCode;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    // 넣을 때 확정하고 바꾸지 않는다. 다시 보낼 때 현재 재고로 다시 만들면 그 사이의 변경이 섞인다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private StockChangedPayload payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private OutboxMessage(
            final CompanyCode companyCode,
            final String aggregateType,
            final String aggregateId,
            final String eventType,
            final StockChangedPayload payload
    ) {
        this.companyCode = companyCode;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
    }

    // 원장 한 건의 재고 변동. 원장을 먼저 저장해 id가 있어야 한다
    public static OutboxMessage stockChanged(
            final CompanyCode companyCode,
            final ProductCode productCode,
            final Long stockTransactionId,
            final StockChange change,
            final OffsetDateTime occurredAt
    ) {
        if (stockTransactionId == null) {
            throw new IllegalStateException("원장을 저장하기 전에는 outbox 메시지를 만들 수 없습니다.");
        }
        final StockChangedPayload payload = new StockChangedPayload(
            stockTransactionId,
            companyCode.value(),
            productCode.value(),
            change.type(),
            change.amount().value(),
            change.before().value(),
            change.after().value(),
            occurredAt.toString()
        );
        return OutboxMessage.builder()
            .companyCode(companyCode)
            .aggregateType(STOCK_TRANSACTION)
            .aggregateId(String.valueOf(stockTransactionId))
            .eventType(STOCK_CHANGED)
            .payload(payload)
            .build();
    }

    @PrePersist
    void onCreate() {
        final OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.availableAt = now;
    }
}
