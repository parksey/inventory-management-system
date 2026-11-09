package com.logistics.inventory.domain.erp;

import com.logistics.inventory.domain.erp.model.ErpDelivery;
import com.logistics.inventory.domain.erp.model.ErpFailureKind;
import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.port.ErpClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ErpSyncSender {

    private final ErpSyncService erpSyncService;
    private final ErpClient erpClient;

    public void sendDue(final String workerId) {
        while (true) {
            final Optional<ErpDelivery> claimed = erpSyncService.claimNext(workerId);
            if (claimed.isEmpty()) {
                return;
            }
            sendOne(claimed.get(), workerId);
        }
    }

    public void checkUnknown(final String workerId) {
        while (true) {
            final Optional<ErpDelivery> claimed = erpSyncService.claimNextUnknown(workerId);
            if (claimed.isEmpty()) {
                return;
            }
            checkOne(claimed.get(), workerId);
        }
    }

    private void sendOne(final ErpDelivery delivery, final String workerId) {
        final ErpSendResult result = send(delivery);
        try {
            erpSyncService.record(delivery.erpSyncId(), workerId, result);
        } catch (final RuntimeException e) {
            erpSyncService.record(delivery.erpSyncId(), workerId, ErpSendResult.unknown("결과 저장 실패: " + e));
        }
    }

    private void checkOne(final ErpDelivery delivery, final String workerId) {
        try {
            final ErpLookupResult result = find(delivery);
            erpSyncService.recordLookup(delivery, workerId, result);
        } catch (final RuntimeException e) {
            handleFailure(delivery.erpSyncId(), workerId, e);
        }
    }

    private void handleFailure(final Long erpSyncId, final String workerId, final RuntimeException e) {
        final ErpFailureKind kind = ErpFailureClassifier.classify(e);
        if (kind != ErpFailureKind.RETRYABLE) {
            try {
                erpSyncService.stop(erpSyncId, workerId, kind + ": " + e);
            } catch (final RuntimeException ignored) {
                // 다음 처리에서 다시 판단
            }
        }
    }

    private ErpLookupResult find(final ErpDelivery delivery) {
        try {
            return erpClient.find(delivery.movement().idempotencyKey());
        } catch (final RuntimeException e) {
            return ErpLookupResult.error(e.toString());
        }
    }

    private ErpSendResult send(final ErpDelivery delivery) {
        try {
            return erpClient.send(delivery.movement());
        } catch (final RuntimeException e) {
            return ErpSendResult.unknown(e.toString());
        }
    }
}
