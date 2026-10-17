package com.logistics.inventory.domain.product.entity;

import com.logistics.inventory.domain.common.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "product_code", nullable = false))
    private ProductCode productCode;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "name", nullable = false))
    private ProductName name;

    @Builder(access = AccessLevel.PRIVATE)
    private Product(
        final ProductCode productCode,
        final ProductName name
    ) {
        this.productCode = productCode;
        this.name = name;
    }

    // 처음 입고되는 상품. 이름은 상품 코드로 시작하고 나중에 바꾼다
    public static Product register(final ProductCode productCode) {
        return Product.builder()
            .productCode(productCode)
            .name(new ProductName(productCode.value()))
            .build();
    }

    public void rename(final ProductName name) {
        this.name = name;
    }
}
