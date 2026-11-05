CREATE TABLE submission_guard (id INTEGER PRIMARY KEY);
INSERT INTO submission_guard(id) VALUES (1);

CREATE TABLE label_submissions (
    id VARCHAR(36) PRIMARY KEY,
    barcode VARCHAR(14) NOT NULL,
    submitted_payload TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    version INTEGER NOT NULL DEFAULT 0,
    submitted_at BIGINT NOT NULL,
    reviewed_at BIGINT,
    reviewer VARCHAR(120),
    decision_note VARCHAR(1000) NOT NULL DEFAULT '',
    reviewed_payload TEXT
);
CREATE INDEX label_submission_queue ON label_submissions(status, submitted_at);

CREATE TABLE submission_photos (
    submission_id VARCHAR(36) NOT NULL REFERENCES label_submissions(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('front','barcode','ingredients')),
    photo_data BYTEA NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    PRIMARY KEY (submission_id, role)
);

-- Published data is independent of replaceable starter imports and provider cache.
CREATE TABLE reviewed_products (
    barcode VARCHAR(14) PRIMARY KEY,
    name TEXT NOT NULL,
    has_ingredients BOOLEAN NOT NULL,
    payload TEXT NOT NULL,
    submission_id VARCHAR(36) NOT NULL REFERENCES label_submissions(id)
);
