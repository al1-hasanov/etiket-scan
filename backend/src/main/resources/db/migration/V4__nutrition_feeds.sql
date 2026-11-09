CREATE TABLE nutrition_import_guard (id INTEGER PRIMARY KEY);
INSERT INTO nutrition_import_guard(id) VALUES (1);

CREATE TABLE nutrition_reports (
    barcode VARCHAR(14) NOT NULL,
    source_id VARCHAR(40) NOT NULL,
    observed_at BIGINT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (barcode, source_id)
);
