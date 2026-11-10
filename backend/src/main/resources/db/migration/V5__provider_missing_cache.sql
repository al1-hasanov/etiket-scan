-- Negative cache is separate from reviewed/local products and from positive OFF payloads.
-- Only a confirmed provider 404 may create a row; transient errors never become misses.
CREATE TABLE product_lookup_misses (
    barcode VARCHAR(14) PRIMARY KEY,
    checked_at BIGINT NOT NULL
);
