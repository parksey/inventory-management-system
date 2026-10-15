package com.logistics.inventory.domain.inventory.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_QUANTITY;

import com.logistics.inventory.domain.common.BaseTimeEntity;
import com.logistics.inventory.domain.common.BusinessException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseTimeEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "quantity", nullable = false))
    private Quantity quantity;

    @Builder(access = AccessLevel.PRIVATE)
    private Inventory(final Long productId, final Quantity quantity) {
        this.productId = Objects.requireNonNull(productId);
        this.quantity = quantity;
    }

    public static Inventory create(final Long productId) {
        return Inventory.builder()
            .productId(productId)
            .quantity(new Quantity(0))
            .build();
    }

    public StockChange increase(final Quantity amount) {
        if (amount.value() == 0) {
            throw BusinessException.of(INVALID_QUANTITY, "productId=%d, 요청=0".formatted(productId));
        }

        final Quantity before = quantity;
        this.quantity = quantity.plus(amount);
        return new StockChange(productId, StockChangeType.INBOUND, amount, before, quantity);
    }
}
