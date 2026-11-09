package com.logistics.inventory.domain.erp;

import com.logistics.inventory.domain.erp.entity.ErpSync;
import com.logistics.inventory.domain.erp.entity.ErpSyncStatus;
import com.logistics.inventory.domain.erp.model.ErpDelivery;
import com.logistics.inventory.domain.erp.model.ErpLookupPolicy;
import com.logistics.inventory.domain.erp.model.ErpLookupResult;
import com.logistics.inventory.domain.erp.model.ErpSendResult;
import com.logistics.inventory.domain.erp.model.ErpStockMovementOutcome;
import com.logistics.inventory.domain.erp.port.ErpSyncStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ERP 전송의 DB 쪽 두 단계: 선점(보내기 전)과 결과 저장(보낸 뒤). 둘 사이의 ERP 호출은 트랜잭션 밖에서 한다(ErpSyncSender)
@Service
@RequiredArgsConstructor
public class ErpSyncService {

    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);

    private final ErpSyncStore erpSyncStore;
    private final ApplicationEventPublisher eventPublisher;
    private final ErpLookupPolicy lookupPolicy;

    // 보낼 차례가 된 건을 한 건 선점하고, 보낼 내용(키, outbox payload)을 꺼낸다. 커밋되면 다른 배치는 이 건을 건너뛴다.
    // 한 건씩 잡아야 선점 시간(60초)이 그 건 하나의 호출 시간만 버티면 된다
    @Transactional
    public Optional<ErpDelivery> claimNext(final String workerId) {
        return claim(erpSyncStore.findDueIdsForUpdate(1), workerId).stream().findFirst();
    }

    // 보냈는지 모르는(UNKNOWN) 건을 한 건 선점한다. 다시 보내지 않고 참조번호로 조회만 한다
    @Transactional
    public Optional<ErpDelivery> claimNextUnknown(final String workerId) {
        return claim(erpSyncStore.findCheckDueIdsForUpdate(1, LOCK_TIMEOUT), workerId).stream().findFirst();
    }

    // 보내던 배치가 죽어 선점이 만료된 PENDING은 보냈는지 모른다. 다시 보내지 않고 UNKNOWN으로 넘긴다
    @Transactional
    public int markExpiredClaimsUnknown() {
        return erpSyncStore.markExpiredClaimsUnknown(LOCK_TIMEOUT);
    }

    private List<ErpDelivery> claim(final List<Long> ids, final String workerId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        erpSyncStore.markClaimed(ids, workerId);
        final List<ErpDelivery> deliveries = erpSyncStore.findDeliveries(ids);
        if (deliveries.size() != ids.size()) {
            throw new IllegalStateException("선점한 erp_sync의 outbox 메시지가 없습니다. ids=" + ids);
        }
        return deliveries;
    }

    // 선점한 배치만 결과를 저장한다. 그 사이 선점이 만료돼 다른 배치가 가져갔으면 아무것도 바꾸지 않는다
    @Transactional
    public void record(final Long erpSyncId, final String workerId, final ErpSendResult result) {
        final ErpSync sync = erpSyncStore.findByIdForUpdate(erpSyncId)
            .orElseThrow(() -> new IllegalStateException("erp_sync가 없습니다. id=" + erpSyncId));
        if (!sync.isLockedBy(workerId)) {
            return;
        }
        final OffsetDateTime now = erpSyncStore.currentTime();
        switch (result.outcome()) {
            case SUCCESS -> sync.markSent(result.documentId(), now);
            case REJECTED -> sync.reject(result.error(), now);
            case NOT_SENT -> sync.retrySend(result.error(), now);
            case UNKNOWN -> sync.markUnknown(result.error(), lookupPolicy.first(), now);
        }
        // 커밋된 뒤 지표와 로그가 받는다
        eventPublisher.publishEvent(new ErpStockMovementOutcome(
            sync.getId(),
            sync.getOutboxMessageId(),
            resultOf(sync, result),
            sync.getAttempts(),
            sync.getLastError()
        ));
    }

    // 조회 결과를 저장한다. 있고 내용이 같으면 SENT, 내용이 다르면 사람 확인,
    // 없거나 조회가 실패하면 UNKNOWN 그대로 두고 정해 둔 간격 뒤에 다시 조회한다
    @Transactional
    public void recordLookup(final ErpDelivery delivery, final String workerId, final ErpLookupResult result) {
        final ErpSync sync = erpSyncStore.findByIdForUpdate(delivery.erpSyncId())
            .orElseThrow(() -> new IllegalStateException("erp_sync가 없습니다. id=" + delivery.erpSyncId()));
        if (!sync.isLockedBy(workerId)) {
            return;
        }
        final OffsetDateTime now = erpSyncStore.currentTime();
        switch (result.outcome()) {
            case FOUND -> {
                if (result.matches(delivery.movement().payload())) {
                    sync.markSent(result.documentId(), now);
                } else {
                    sync.markMismatch(result.documentId(), "ERP 내용 불일치: 보냄=" + delivery.movement().payload() + ", ERP=" + result.recorded(), now);
                }
            }
            case NOT_FOUND, ERROR -> sync.missLookup(result.error(), lookupPolicy, now);
        }
        eventPublisher.publishEvent(new ErpStockMovementOutcome(
            sync.getId(),
            sync.getOutboxMessageId(),
            lookupResultOf(sync, result),
            sync.getAttempts(),
            sync.getLastError()
        ));
    }

    // 다시 해도 안 되는 실패로 결과를 저장하지 못한 건의 자동 처리를 멈춘다. 선점한 배치만 멈출 수 있다
    @Transactional
    public void stop(final Long erpSyncId, final String workerId, final String reason) {
        final ErpSync sync = erpSyncStore.findByIdForUpdate(erpSyncId)
            .orElseThrow(() -> new IllegalStateException("erp_sync가 없습니다. id=" + erpSyncId));
        if (!sync.isLockedBy(workerId)) {
            return;
        }
        sync.stop(reason, erpSyncStore.currentTime());
        eventPublisher.publishEvent(new ErpStockMovementOutcome(
            sync.getId(),
            sync.getOutboxMessageId(),
            "STOPPED",
            sync.getAttempts(),
            sync.getLastError()
        ));
    }

    private static String lookupResultOf(final ErpSync sync, final ErpLookupResult result) {
        if (sync.getStatus() == ErpSyncStatus.SENT) {
            return "SENT";
        }
        if (result.outcome() == ErpLookupResult.Outcome.FOUND) {
            return "MISMATCH";
        }
        return sync.isStopped() ? "STOPPED" : "LOOKUP_" + result.outcome().name();
    }

    private static String resultOf(final ErpSync sync, final ErpSendResult result) {
        if (result.outcome() != ErpSendResult.Outcome.REJECTED && sync.isStopped()) {
            return "STOPPED";
        }
        return switch (result.outcome()) {
            case SUCCESS -> "SENT";
            case REJECTED -> "REJECTED";
            case NOT_SENT -> "NOT_SENT";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
