-- Keep migrations V1/V2 and existing photo roles intact.
CREATE TABLE submission_nutrition_photos (
    submission_id VARCHAR(36) PRIMARY KEY REFERENCES label_submissions(id),
    photo_data BYTEA NOT NULL, sha256 VARCHAR(64) NOT NULL,
    width INTEGER NOT NULL, height INTEGER NOT NULL
);
CREATE TABLE label_analysis_jobs (
    id VARCHAR(36) PRIMARY KEY, access_hash VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL, language VARCHAR(12) NOT NULL,
    photo_data BYTEA, photo_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
    lease_until BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,
    output TEXT NOT NULL, error_code VARCHAR(80) NOT NULL DEFAULT ''
);
CREATE INDEX label_analysis_queue ON label_analysis_jobs(status, created_at);
CREATE TABLE submission_ocr (
    submission_id VARCHAR(36) NOT NULL REFERENCES label_submissions(id),
    role VARCHAR(16) NOT NULL, snapshot TEXT NOT NULL,
    PRIMARY KEY (submission_id, role)
);
