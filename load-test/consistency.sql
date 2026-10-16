-- 부하 테스트 뒤 정합성 확인. 위반 건수를 출력하고, 모두 0이어야 한다
SELECT '음수 재고' AS "확인", COUNT(*) AS "위반"
FROM inventory
WHERE quantity < 0
UNION ALL
SELECT '재고 ≠ 원장 합계', COUNT(*)
FROM inventory i
WHERE i.quantity <> (
    SELECT COALESCE(SUM(CASE t.type WHEN 'INBOUND' THEN t.quantity ELSE -t.quantity END), 0)
    FROM stock_transaction t
    WHERE t.product_id = i.product_id
)
UNION ALL
SELECT '멱등성 기록 ≠ 원장', ABS((SELECT COUNT(*) FROM idempotency_record) - (SELECT COUNT(*) FROM stock_transaction));
