-- 상품: 회사마다 상품 코드가 따로다. 기존 상품은 기본 회사로 옮기고, 기본값은 바로 지워 회사 코드를 빠뜨린 INSERT가 드러나게 한다
ALTER TABLE product ADD COLUMN company_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE product ALTER COLUMN company_code DROP DEFAULT;
ALTER TABLE product DROP CONSTRAINT uk_product_product_code;
-- 회사 + 상품 코드로 상품을 찾고 등록한다(ON CONFLICT). 인덱스를 먼저 만들고 유니크 제약이 그 인덱스를 쓴다
CREATE UNIQUE INDEX uk_product_company_code_product_code ON product (company_code, product_code);
ALTER TABLE product ADD CONSTRAINT uk_product_company_code_product_code UNIQUE USING INDEX uk_product_company_code_product_code;

-- 멱등성 기록: 회사마다 키가 따로다. 기존 기록은 기본 회사로
ALTER TABLE idempotency_record ADD COLUMN company_code VARCHAR(64) NOT NULL DEFAULT 'DEFAULT';
ALTER TABLE idempotency_record ALTER COLUMN company_code DROP DEFAULT;
ALTER TABLE idempotency_record DROP CONSTRAINT idempotency_record_pkey;
-- 회사 + 키로 넣고(ON CONFLICT) 다시 읽는다. 인덱스를 먼저 만들고 PK가 그 인덱스를 쓴다
CREATE UNIQUE INDEX pk_idempotency_record ON idempotency_record (company_code, idempotency_key);
ALTER TABLE idempotency_record ADD CONSTRAINT pk_idempotency_record PRIMARY KEY USING INDEX pk_idempotency_record;
