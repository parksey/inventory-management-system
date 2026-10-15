-- 상품별 이력을 id 역순으로 읽는 조회용
CREATE INDEX ix_stock_transaction_product_id_id ON stock_transaction (product_id, id);
