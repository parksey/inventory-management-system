package com.logistics.inventory.domain.erp.entity;

import com.logistics.inventory.domain.common.RetryPolicy;
import com.logistics.inventory.domain.erp.model.ErpLookupPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 원장 한 건(outbox 메시지 하나)의 ERP 전달 상태.
@Entity
@Table(name = "erp_sync")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ErpSync {

    // 이 횟수만큼 보내고도 안 되면 자동 재시도를 멈추고 사람에게 넘긴다
    public static final int MAX_ATTEMPTS = RetryPolicy.MAX_ATTEMPTS;

    @Id
    private Long id;

    @Column(name = "outbox_message_id", nullable = false, updatable = false)
    private Long outboxMessageId;

    @Column(name = "erp_idempotency_key", nullable = false, updatable = false)
    private String erpIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ErpSyncStatus status;

    @Column(nullable = false)
    private int attempts;

    // 참조번호로 조회해서 못 찾은 횟수
    @Column(nullable = false)
    private int lookups;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "erp_document_id")
    private String erpDocumentId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    // n번째 전송이 연결 실패로 끝났을 때 다음 전송까지 기다리는 시간: 1, 2, 4, 8초. 5번째에서 멈춘다
    public static Duration backoff(final int attempts) {
        return RetryPolicy.backoff(attempts);
    }

    // 선점한 배치만 결과를 저장한다. 선점이 만료돼 다른 배치가 가져간 뒤 늦게 끝난 배치는 덮어쓰지 않는다
    // erp_sync 참조번호: 원장 id 기준이라 같은 원장이면 늘 같다
    public static String idempotencyKeyOf(final String stockTransactionId) {
        return "stx-" + stockTransactionId;
    }

    public boolean isLockedBy(final String workerId) {
        return workerId.equals(lockedBy);
    }

    // 시각(now)은 DB 시계로 받는다. 배치가 next_retry_at을 DB의 now()와 비교하기 때문이다
    public void markSent(final String documentId, final OffsetDateTime now) {
        this.status = ErpSyncStatus.SENT;
        this.erpDocumentId = documentId;
        this.sentAt = now;
        this.nextRetryAt = null;
        release();
    }

    // ERP가 확실히 거절했다. 같은 요청을 다시 보내도 같은 결과라 재시도하지 않는다
    public void reject(final String error, final OffsetDateTime now) {
        this.status = ErpSyncStatus.FAILED;
        this.lastError = error;
        this.failedAt = now;
        this.nextRetryAt = null;
        release();
    }

    // 요청이 나가지 못했다(연결 실패). 횟수가 남았으면 백오프 뒤 다시 보내고, 다 썼으면 멈춘다
    public void retrySend(final String error, final OffsetDateTime now) {
        this.status = ErpSyncStatus.PENDING;
        this.lastError = error;
        if (RetryPolicy.isExhausted(attempts)) {
            stopAt(now);
        } else {
            this.nextRetryAt = now.plus(backoff(attempts));
        }
        release();
    }

    // 보냈는데 결과를 모른다. 다시 보내지 않고 기다렸다가 참조번호로 조회한다
    public void markUnknown(final String error, final Duration untilLookup, final OffsetDateTime now) {
        this.status = ErpSyncStatus.UNKNOWN;
        this.lastError = error;
        this.nextRetryAt = now.plus(untilLookup);
        release();
    }

    // 조회해도 못 찾았다(또는 조회 실패). 정해 둔 간격이 남았으면 그 뒤 다시 조회하고, 다 썼으면 멈춘다
    public void missLookup(final String error, final ErpLookupPolicy policy, final OffsetDateTime now) {
        this.lookups++;
        this.lastError = error;
        policy.afterLookups(lookups).ifPresentOrElse(
            delay -> this.nextRetryAt = now.plus(delay),
            () -> stopAt(now)
        );
        release();
    }

    // ERP에 있긴 한데 우리가 보낸 내용과 다르다. 자동으로 고치지 않고 사람이 확인한다
    public void markMismatch(final String documentId, final String reason, final OffsetDateTime now) {
        this.erpDocumentId = documentId;
        this.lastError = reason;
        stopAt(now);
        release();
    }

    // 우리 쪽에서 다시 해도 안 되는 실패가 났다. 상태는 그대로 두고 자동 재시도만 멈춘다(사람 확인 대상)
    public void stop(final String reason, final OffsetDateTime now) {
        this.lastError = reason;
        stopAt(now);
        release();
    }

    private void stopAt(final OffsetDateTime now) {
        this.nextRetryAt = null;
        this.failedAt = now;
    }

    // 자동으로는 더 하지 않는 상태(사람 확인 대상)
    public boolean isStopped() {
        return status == ErpSyncStatus.FAILED
            || (status != ErpSyncStatus.SENT && nextRetryAt == null);
    }

    private void release() {
        this.lockedBy = null;
        this.lockedAt = null;
    }
}
