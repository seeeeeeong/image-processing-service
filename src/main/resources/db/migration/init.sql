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
        CASE WHEN status NOT IN ('COMPLETED', 'FAILED', 'DEAD_LETTER')
             THEN payload_hash
             ELSE NULL
        END
    ) STORED,

    UNIQUE KEY idx_jobs_idempotency_key (idempotency_key),
    UNIQUE KEY idx_jobs_active_dedup (active_dedup_key),
    KEY idx_jobs_queued_next_attempt (status, next_attempt_at),
    KEY idx_jobs_processing_poll_due (status, poll_due_at),
    KEY idx_jobs_lease_recovery (status, locked_until),
    KEY idx_jobs_external_job_id (external_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
