package com.logistics.inventory.domain.idempotency.entity;

import static com.logistics.inventory.domain.common.ErrorCode.IDEMPOTENCY_KEY_REUSED;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.inventory.entity.Quantity;
import com.logistics.inventory.domain.inventory.entity.StockChangeType;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.transaction.entity.RequesterId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 처리한 멱등성 키와 그 요청 내용. 재전송인지, 키를 다른 요청에 다시 쓴 것인지 가린다
@Entity
@Table(name = "idempotency_record")
@IdClass(IdempotencyRecordId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Getter(AccessLevel.NONE)
    @Column(name = "company_code", nullable = false, updatable = false)
    private String companyCode;

    @Id
    @Getter(AccessLevel.NONE)
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StockChangeType type;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "product_code", nullable = false, updatable = false))
    private ProductCode productCode;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "quantity", nullable = false, updatable = false))
    private Quantity quantity;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "requester_id", nullable = false, updatable = false))
    private RequesterId requesterId;

    @Builder(access = AccessLevel.PRIVATE)
    private IdempotencyRecord(
            final CompanyCode companyCode,
            final IdempotencyKey idempotencyKey,
            final StockChangeType type,
            final ProductCode productCode,
            final Quantity quantity,
            final RequesterId requesterId
    ) {
        this.companyCode = companyCode.value();
        this.idempotencyKey = idempotencyKey.value();
        this.type = type;
        this.productCode = productCode;
        this.quantity = quantity;
        this.requesterId = requesterId;
    }

    public static IdempotencyRecord of(
            final CompanyCode companyCode,
            final IdempotencyKey idempotencyKey,
            final StockChangeType type,
            final ProductCode productCode,
            final Quantity quantity,
            final RequesterId requesterId
    ) {
        return IdempotencyRecord.builder()
            .companyCode(companyCode)
            .idempotencyKey(idempotencyKey)
            .type(type)
            .productCode(productCode)
            .quantity(quantity)
            .requesterId(requesterId)
            .build();
    }

    public CompanyCode getCompanyCode() {
        return new CompanyCode(companyCode);
    }

    public IdempotencyKey getIdempotencyKey() {
        return new IdempotencyKey(idempotencyKey);
    }

    public void verifySameRequest(final IdempotencyRecord request) {
        final List<String> differences = new ArrayList<>();
        if (type != request.type) {
            differences.add("type");
        }
        if (!productCode.equals(request.productCode)) {
            differences.add("productCode");
        }
        if (!quantity.equals(request.quantity)) {
            differences.add("quantity");
        }
        if (!requesterId.equals(request.requesterId)) {
            differences.add("requesterId");
        }
        if (!differences.isEmpty()) {
            throw BusinessException.of(IDEMPOTENCY_KEY_REUSED, "idempotencyKey=" + idempotencyKey + ", 다른 항목=" + differences);
        }
    }
}
