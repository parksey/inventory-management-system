CREATE TABLE product (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code  VARCHAR(64)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT uk_product_product_code UNIQUE (product_code)
);

CREATE TABLE inventory (
    product_id  BIGINT      PRIMARY KEY REFERENCES product (id),
    quantity    BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT ck_inventory_quantity_non_negative CHECK (quantity >= 0)
);
