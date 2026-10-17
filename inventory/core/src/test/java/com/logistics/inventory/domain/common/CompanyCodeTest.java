package com.logistics.inventory.domain.common;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_COMPANY_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class CompanyCodeTest {

    @ParameterizedTest
    @NullAndEmptySource
    @MethodSource("invalidCodes")
    @DisplayName("TC-40-01 비었거나 공백, 소문자가 있거나 65자 이상이면 INVALID_COMPANY_CODE")
    void 잘못된_코드(final String value) {
        assertThatThrownBy(() -> new CompanyCode(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(INVALID_COMPANY_CODE);
    }

    @Test
    @DisplayName("TC-40-01 영문 대문자, 숫자, 하이픈으로 된 64자 코드는 만들어진다")
    void 올바른_코드() {
        assertThat(new CompanyCode("A".repeat(64)).value()).hasSize(64);
        assertThat(new CompanyCode("COMPANY-A").value()).isEqualTo("COMPANY-A");
    }

    static Stream<String> invalidCodes() {
        return Stream.of(" ", "company-a", "COMPANY A", "A".repeat(65));
    }
}
