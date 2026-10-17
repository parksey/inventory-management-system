package com.logistics.inventory.domain.common;

import static com.logistics.inventory.domain.common.ErrorCode.INVALID_COMPANY_CODE;

import jakarta.persistence.Embeddable;
import java.util.regex.Pattern;

// 시스템을 함께 쓰는 회사(테넌트). 데이터가 어느 회사의 것인지 정한다
@Embeddable
public record CompanyCode(
    String value
) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9-]{1,64}");

    public CompanyCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw BusinessException.of(INVALID_COMPANY_CODE, "value=" + value);
        }
    }
}
