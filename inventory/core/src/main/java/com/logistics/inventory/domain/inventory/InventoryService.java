package com.logistics.inventory.domain.inventory;

import static com.logistics.inventory.domain.common.ErrorCode.PRODUCT_NOT_FOUND;
import static com.logistics.inventory.domain.inventory.entity.StockChangeType.INBOUND;
import static com.logistics.inventory.domain.inventory.entity.StockChangeType.OUTBOUND;

import com.logistics.inventory.domain.common.BusinessException;
import com.logistics.inventory.domain.common.CompanyCode;
import com.logistics.inventory.domain.common.CursorQuery;
import com.logistics.inventory.domain.common.CursorResult;
import com.logistics.inventory.domain.idempotency.entity.IdempotencyKey;
import com.logistics.inventory.domain.idempotency.entity.IdempotencyRecord;
import com.logistics.inventory.domain.idempotency.port.IdempotencyStore;
import com.logistics.inventory.domain.inventory.entity.Inventory;
import com.logistics.inventory.domain.inventory.entity.StockChange;
import com.logistics.inventory.domain.inventory.model.InboundCommand;
import com.logistics.inventory.domain.inventory.model.OutboundCommand;
import com.logistics.inventory.domain.inventory.model.StockResult;
import com.logistics.inventory.domain.inventory.port.InventoryStore;
import com.logistics.inventory.domain.product.entity.Product;
import com.logistics.inventory.domain.product.entity.ProductCode;
import com.logistics.inventory.domain.product.port.ProductStore;
import com.logistics.inventory.domain.transaction.entity.StockTransaction;
import com.logistics.inventory.domain.transaction.port.StockTransactionStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRegistrar productRegistrar;
    private final ProductStore productStore;
    private final InventoryStore inventoryStore;
    private final StockTransactionStore stockTransactionStore;
    private final IdempotencyStore idempotencyStore;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public StockResult getStock(final CompanyCode companyCode, final ProductCode productCode) {
        return inventoryStore.findStockByProductCode(companyCode, productCode).orElseThrow(() ->
            BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + productCode.value())
        );
    }

    @Transactional(readOnly = true)
    public CursorResult<StockTransaction> getHistory(
            final CompanyCode companyCode,
            final ProductCode productCode,
            final CursorQuery cursorQuery
    ) {
        // 상품 (Product) — 없으면 404. 원장과 조인하면 "상품 없음"과 "이력 없음"을 구분할 수 없어 따로 찾는다
        final Product product = productStore.findByProductCode(companyCode, productCode).orElseThrow(() ->
            BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + productCode.value())
        );
        // 원장 (StockTransaction)
        return stockTransactionStore.findHistoryBy(product.getId(), cursorQuery);
    }

    @Transactional
    public void inbound(final IdempotencyKey idempotencyKey, final InboundCommand command) {
        // 멱등성 기록 (IdempotencyRecord) — 이미 성공한 키면 내용만 확인하고 다시 반영하지 않는다
        final IdempotencyRecord request = IdempotencyRecord.of(
            command.companyCode(), idempotencyKey, INBOUND, command.productCode(), command.quantity(), command.requesterId()
        );
        final Optional<IdempotencyRecord> processed = idempotencyStore.saveOrGetProcessed(request);
        if (processed.isPresent()) {
            processed.get().verifySameRequest(request);
            return;
        }
        // 상품 (Product) — 없으면 수량 0인 재고와 함께 등록
        final Product product = productRegistrar.getOrRegister(command.companyCode(), command.productCode());
        // 재고 (Inventory) — 행 잠금 후 증가
        final Inventory inventory = getInventoryForUpdate(product.getId());
        final StockChange change = inventory.increase(command.quantity());
        // 원장 (StockTransaction)
        stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
        // 커밋된 뒤 지표와 로그가 받는다. 롤백되면 전달되지 않는다
        eventPublisher.publishEvent(change);
    }

    @Transactional
    public void outbound(final IdempotencyKey idempotencyKey, final OutboundCommand command) {
        // 멱등성 기록 (IdempotencyRecord) — 이미 성공한 키면 내용만 확인하고 다시 반영하지 않는다
        final IdempotencyRecord request = IdempotencyRecord.of(
            command.companyCode(), idempotencyKey, OUTBOUND, command.productCode(), command.quantity(), command.requesterId()
        );
        final Optional<IdempotencyRecord> processed = idempotencyStore.saveOrGetProcessed(request);
        if (processed.isPresent()) {
            processed.get().verifySameRequest(request);
            return;
        }
        // 상품 (Product) — 없으면 404. 출고는 상품을 만들지 않는다
        final Product product = productStore.findByProductCode(command.companyCode(), command.productCode())
            .orElseThrow(() -> BusinessException.of(PRODUCT_NOT_FOUND, "productCode=" + command.productCode().value()));
        // 재고 (Inventory) — 행 잠금 후 감소. 재고 부족은 엔티티가 판단한다
        final Inventory inventory = getInventoryForUpdate(product.getId());
        final StockChange change = inventory.decrease(command.quantity());
        // 원장 (StockTransaction)
        stockTransactionStore.save(StockTransaction.record(change, command.requesterId()));
        // 커밋된 뒤 지표와 로그가 받는다. 롤백되면 전달되지 않는다
        eventPublisher.publishEvent(change);
    }

    private Inventory getInventoryForUpdate(final Long productId) {
        return inventoryStore.findByProductIdForUpdate(productId)
            .orElseThrow(() -> new IllegalStateException("상품은 있는데 재고 행이 없습니다. productId=" + productId));
    }
}
