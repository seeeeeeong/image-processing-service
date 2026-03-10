SET @add_poll_failure_count = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'jobs'
          AND COLUMN_NAME = 'poll_failure_count'
    ),
    'SELECT 1',
    'ALTER TABLE jobs ADD COLUMN poll_failure_count INT NOT NULL DEFAULT 0'
);
PREPARE stmt FROM @add_poll_failure_count;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_max_poll_failures = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'jobs'
          AND COLUMN_NAME = 'max_poll_failures'
    ),
    'SELECT 1',
    'ALTER TABLE jobs ADD COLUMN max_poll_failures INT NOT NULL DEFAULT 5'
);
PREPARE stmt FROM @add_max_poll_failures;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
