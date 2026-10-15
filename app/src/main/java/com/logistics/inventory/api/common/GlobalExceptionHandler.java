package com.logistics.inventory.api.common;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(final BusinessException e, final HttpServletRequest request) {
        final ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외: code={}, path={}, detail={}", errorCode.name(), request.getRequestURI(), e.getMessage());

        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorStatusMapper.toStatus(errorCode.getKind()), e.getMessage());
        problem.setProperty("code", errorCode.name());

        return problem;
    }
}
