CREATE TABLE IF NOT EXISTS jobs (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    idempotency_key   VARCHAR(255),
    payload_hash      VARCHAR(64)  NOT NULL,
    image_url         TEXT         NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'QUEUED',
    external_job_id   VARCHAR(255),
    attempt_count     INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 3,
    next_attempt_at   DATETIME(6),
    poll_due_at       DATETIME(6),
    locked_until      DATETIME(6),
    result            TEXT,
    last_error_code   VARCHAR(100),
    last_error_message TEXT,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,

    active_dedup_key  VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN idempotency_key IS NULL
              AND status NOT IN ('COMPLETED', 'FAILED', 'DEAD_LETTER')
             THEN payload_hash
             ELSE NULL
        END
    ) STORED
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE UNIQUE INDEX idx_jobs_idempotency_key ON jobs (idempotency_key);
CREATE UNIQUE INDEX idx_jobs_active_dedup ON jobs (active_dedup_key);
CREATE INDEX idx_jobs_queued_next_attempt ON jobs (status, next_attempt_at);
CREATE INDEX idx_jobs_processing_poll_due ON jobs (status, poll_due_at);
CREATE INDEX idx_jobs_lease_recovery ON jobs (status, locked_until);
CREATE INDEX idx_jobs_external_job_id ON jobs (external_job_id);
