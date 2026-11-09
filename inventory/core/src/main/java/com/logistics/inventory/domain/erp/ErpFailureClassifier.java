package com.logistics.inventory.domain.erp;

import com.logistics.inventory.domain.erp.model.ErpFailureKind;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class ErpFailureClassifier {

    private ErpFailureClassifier() {
    }

    public static ErpFailureKind classify(final RuntimeException e) {
        if (e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException
                || e instanceof DataAccessResourceFailureException
                || e instanceof CannotCreateTransactionException) {
            return ErpFailureKind.RETRYABLE;
        }
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            return ErpFailureKind.PERMANENT;
        }
        return ErpFailureKind.REQUIRES_REVIEW;
    }
}
