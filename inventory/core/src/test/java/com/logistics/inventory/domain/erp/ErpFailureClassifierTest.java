package com.logistics.inventory.domain.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.logistics.inventory.domain.erp.model.ErpFailureKind;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

class ErpFailureClassifierTest {

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("failures")
    @DisplayName("TC-48-24 기다리면 될 실패는 RETRYABLE, 다시 해도 같은 실패는 PERMANENT, 모르는 실패는 REQUIRES_REVIEW")
    void 분류(final RuntimeException failure, final ErpFailureKind expected) {
        assertThat(ErpFailureClassifier.classify(failure)).isEqualTo(expected);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new PessimisticLockingFailureException("잠금 대기 초과"), ErpFailureKind.RETRYABLE),
                Arguments.of(new CannotAcquireLockException("잠금 획득 실패"), ErpFailureKind.RETRYABLE),
                Arguments.of(new QueryTimeoutException("쿼리 타임아웃"), ErpFailureKind.RETRYABLE),
                Arguments.of(new DataAccessResourceFailureException("연결 끊김"), ErpFailureKind.RETRYABLE),
                Arguments.of(new CannotCreateTransactionException("DB 다운"), ErpFailureKind.RETRYABLE),
                Arguments.of(new IllegalStateException("선점한 행이 없음"), ErpFailureKind.PERMANENT),
                Arguments.of(new IllegalArgumentException("허용하지 않는 상태 전이"), ErpFailureKind.PERMANENT),
                Arguments.of(new NullPointerException(), ErpFailureKind.REQUIRES_REVIEW)
        );
    }
}
