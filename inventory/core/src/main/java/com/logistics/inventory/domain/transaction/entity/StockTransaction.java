package com.logistics.inventory.domain.transaction.entity;

import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.inventory.entity.StockChange;
import com.logistics.inventory.domain.inventory.entity.StockChangeType;
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

// 재고 변동 원장. 쓰고 나면 바꾸지 않는다
@Entity
@Table(name = "stock_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StockChangeType type;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "quantity", nullable = false, updatable = false))
    private Quantity quantity;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "before_quantity", nullable = false, updatable = false))
    private Quantity beforeQuantity;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "after_quantity", nullable = false, updatable = false))
    private Quantity afterQuantity;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "requester_id", nullable = false, updatable = false))
    private RequesterId requesterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private StockTransaction(
            final Long productId,
            final StockChangeType type,
            final Quantity quantity,
            final Quantity beforeQuantity,
            final Quantity afterQuantity,
            final RequesterId requesterId
    ) {
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.requesterId = requesterId;
    }

    public static StockTransaction record(final StockChange change, final RequesterId requesterId) {
        return StockTransaction.builder()
            .productId(change.productId())
            .type(change.type())
            .quantity(change.amount())
            .beforeQuantity(change.before())
            .afterQuantity(change.after())
            .requesterId(requesterId)
            .build();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
