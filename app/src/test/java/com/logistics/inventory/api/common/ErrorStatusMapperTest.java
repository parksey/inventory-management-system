package com.logistics.inventory.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.domain.common.ErrorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorStatusMapperTest {

    @Test
    @DisplayName("TC-20-02 ErrorKind는 INVALID 400, NOT_FOUND 404, CONFLICT 409, UNPROCESSABLE 422로 매핑된다")
    void 종류별_상태() {
        assertThat(ErrorStatusMapper.toStatus(ErrorKind.INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorStatusMapper.toStatus(ErrorKind.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorStatusMapper.toStatus(ErrorKind.CONFLICT)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorStatusMapper.toStatus(ErrorKind.UNPROCESSABLE).value()).isEqualTo(422);
    }
}
