package com.logistics.inventory.api.common;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final MeterRegistry registry;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(final BusinessException e, final HttpServletRequest request) {
        final ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외: code={}, path={}, detail={}", errorCode.name(), request.getRequestURI(), e.getMessage());
        // 거절 사유별 건수. 상태 코드로만 세면 같은 409로 나가는 다른 에러와 섞인다
        registry.counter("inventory.business.error", "code", errorCode.name()).increment();

        final HttpStatus status = ErrorStatusMapper.toStatus(errorCode.getKind());
        return ResponseEntity.status(status)
            .body(ApiResponse.error(status, errorCode.name(), e.getMessage(), null));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockTimeout(final PessimisticLockingFailureException e, final HttpServletRequest request) {
        log.warn("락 대기 초과: path={}, cause={}", request.getRequestURI(), e.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(final Exception e, final HttpServletRequest request) {
        log.error("처리되지 않은 예외: path={}", request.getRequestURI(), e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, null));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            final Exception e,
            final Object body,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final WebRequest request
    ) {
        final HttpStatus httpStatus = HttpStatus.valueOf(status.value());
        log.warn("요청 오류: status={}, path={}, cause={}", status.value(), pathOf(request), e.getClass().getSimpleName());

        final List<InvalidField> errors = invalidFieldsOf(e);
        final ApiResponse<List<InvalidField>> response = ApiResponse.error(httpStatus, errors.isEmpty() ? null : errors);

        return super.handleExceptionInternal(e, response, headers, status, request);
    }

    // 검증 실패의 틀린 항목과 이유. 클라이언트가 보낸 값은 되돌려 주지 않는다
    private static List<InvalidField> invalidFieldsOf(final Exception e) {
        return switch (e) {
            case MethodArgumentNotValidException ex -> ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                    .toList();
            case HandlerMethodValidationException ex -> ex.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> new InvalidField(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                    .toList();
            case MissingRequestHeaderException ex -> List.of(new InvalidField(ex.getHeaderName(), "필수 헤더가 없습니다"));
            default -> List.of();
        };
    }

    record InvalidField(String field, String reason) {
    }

    private static String pathOf(final WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : request.getDescription(false);
    }
}
