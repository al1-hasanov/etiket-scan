CREATE TABLE IF NOT EXISTS product_cache (
    barcode VARCHAR(14) PRIMARY KEY,
    payload TEXT NOT NULL,
    stored_at BIGINT NOT NULL
);

CREATE TABLE catalogue_imports (
    dataset_id VARCHAR(80) PRIMARY KEY,
    digest VARCHAR(64) NOT NULL,
    record_count INTEGER NOT NULL,
    imported_at VARCHAR(40) NOT NULL
);

INSERT INTO catalogue_imports(dataset_id,digest,record_count,imported_at)
VALUES ('bazarstore-starter','',0,'');

CREATE TABLE catalogue_products (
    barcode VARCHAR(14) PRIMARY KEY,
    dataset_id VARCHAR(80) NOT NULL REFERENCES catalogue_imports(dataset_id),
    name TEXT NOT NULL,
    brand TEXT NOT NULL,
    has_ingredients BOOLEAN NOT NULL,
    payload TEXT NOT NULL,
    source_record TEXT NOT NULL
);
