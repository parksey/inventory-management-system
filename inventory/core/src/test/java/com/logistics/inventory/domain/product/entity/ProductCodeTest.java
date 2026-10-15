package com.logistics.inventory.domain.product.entity;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_PRODUCT_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistics.inventory.domain.common.BusinessException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class ProductCodeTest {

    @ParameterizedTest
    @MethodSource("validCodes")
    @DisplayName("TC-7-01 영문 대문자, 숫자, 하이픈으로 된 64자 이하 코드는 만들어진다")
    void 올바른_코드(final String value) {
        assertThat(new ProductCode(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("invalidCodes")
    @DisplayName("TC-7-02 비었거나 65자 이상이거나 소문자, 공백, 밑줄이 있으면 INVALID_PRODUCT_CODE")
    void 잘못된_코드(final String value) {
        assertThatThrownBy(() -> new ProductCode(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_PRODUCT_CODE);
    }

    static Stream<String> validCodes() {
        return Stream.of("TS-BASIC-BLK-M", "A".repeat(64));
    }

    static Stream<String> invalidCodes() {
        return Stream.of("A".repeat(65), "ts-basic", "TS BASIC", "TS_BASIC");
    }
}
