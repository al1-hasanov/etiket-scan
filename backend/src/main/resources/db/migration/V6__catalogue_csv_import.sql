CREATE TABLE imported_catalogue_products (
    barcode VARCHAR(14) PRIMARY KEY,
    name TEXT NOT NULL,
    has_ingredients BOOLEAN NOT NULL,
    payload TEXT NOT NULL,
    source_record TEXT NOT NULL
);
