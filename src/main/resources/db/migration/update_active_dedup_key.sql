SET @rebuild_active_dedup_key = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'jobs'
          AND COLUMN_NAME = 'active_dedup_key'
          AND LOWER(GENERATION_EXPRESSION) LIKE '%idempotency_key%'
    ),
    'ALTER TABLE jobs DROP INDEX idx_jobs_active_dedup, DROP COLUMN active_dedup_key, ADD COLUMN active_dedup_key VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status NOT IN (''COMPLETED'', ''FAILED'', ''DEAD_LETTER'') THEN payload_hash ELSE NULL END) STORED, ADD UNIQUE INDEX idx_jobs_active_dedup (active_dedup_key)',
    'SELECT 1'
);
PREPARE stmt FROM @rebuild_active_dedup_key;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
