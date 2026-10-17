package com.logistics.inventory.domain.product.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_PRODUCT_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductNameTest {

    @Test
    @DisplayName("TC-14-01 공백이 아닌 1~200자 상품명은 만들어진다")
    void 정상_이름() {
        assertThat(new ProductName("베이직 반팔 티셔츠 검정 M").value()).isEqualTo("베이직 반팔 티셔츠 검정 M");
        assertThat(new ProductName("가".repeat(200)).value()).hasSize(200);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("TC-14-02 비었거나 공백뿐인 상품명은 INVALID_PRODUCT_NAME")
    void 빈_이름(final String value) {
        assertInvalid(value);
    }

    @Test
    @DisplayName("TC-14-02 201자 상품명은 INVALID_PRODUCT_NAME")
    void 긴_이름() {
        assertInvalid("가".repeat(201));
    }

    private static void assertInvalid(final String value) {
        assertThatThrownBy(() -> new ProductName(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_PRODUCT_NAME);
    }
}
