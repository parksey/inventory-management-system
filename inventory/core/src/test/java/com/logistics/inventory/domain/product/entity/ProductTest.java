package com.logistics.inventory.domain.product.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    @DisplayName("TC-12-03 새로 등록한 상품의 이름은 상품 코드다")
    void 등록() {
        final Product product = Product.register(new ProductCode("TS-BASIC-BLK-M"));

        assertThat(product.getProductCode()).isEqualTo(new ProductCode("TS-BASIC-BLK-M"));
        assertThat(product.getName()).isEqualTo(new ProductName("TS-BASIC-BLK-M"));
    }

    @Test
    @DisplayName("TC-14-03 상품명을 바꾸면 새 이름이 된다")
    void 이름_수정() {
        final Product product = Product.register(new ProductCode("TS-BASIC-BLK-M"));

        product.rename(new ProductName("베이직 반팔 티셔츠 검정 M"));

        assertThat(product.getName()).isEqualTo(new ProductName("베이직 반팔 티셔츠 검정 M"));
    }
}
