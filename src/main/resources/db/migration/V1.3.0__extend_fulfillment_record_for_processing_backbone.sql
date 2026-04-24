ALTER TABLE `fulfillment_record`
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL
    COMMENT 'Fulfillment status: PENDING, PROCESSING, SUCCEEDED, FAILED',
  ADD COLUMN `current_attempt_id` VARCHAR(64) DEFAULT NULL
    COMMENT 'Current executing fulfillment attempt when status = PROCESSING'
    AFTER `channel_context_json`,
  ADD COLUMN `processing_started_at` DATETIME(3) DEFAULT NULL
    COMMENT 'Timestamp when the current attempt formally claimed execution'
    AFTER `current_attempt_id`,
  ADD COLUMN `terminal_at` DATETIME(3) DEFAULT NULL
    COMMENT 'Timestamp when fulfillment reached SUCCEEDED or FAILED'
    AFTER `processing_started_at`,
  ADD COLUMN `execution_path` VARCHAR(64) NOT NULL DEFAULT 'DEFAULT_PROVIDER'
    COMMENT 'Execution path frozen to DEFAULT_PROVIDER in Stage A'
    AFTER `terminal_at`,
  ADD COLUMN `delivery_result_json` JSON DEFAULT NULL
    COMMENT 'Structured delivery result persisted together with SUCCEEDED'
    AFTER `execution_path`,
  ADD COLUMN `last_failure_json` JSON DEFAULT NULL
    COMMENT 'Structured failure summary persisted together with FAILED'
    AFTER `delivery_result_json`,
  ADD COLUMN `last_diagnostic_trace_json` JSON DEFAULT NULL
    COMMENT 'Latest structured diagnostic trace for processing governance'
    AFTER `last_failure_json`;

UPDATE `fulfillment_record`
SET `last_diagnostic_trace_json` = JSON_OBJECT(
  'traceId', CONCAT('backfill-', `fulfillment_id`),
  'decision', 'LEGACY_TRACE_BACKFILL',
  'observedAt', CONCAT(DATE_FORMAT(COALESCE(`updated_at`, `created_at`), '%Y-%m-%dT%H:%i:%s.%f'), 'Z'),
  'tags', JSON_OBJECT(
    'source', 'migration',
    'reason', 'legacy-null-backfill'
  )
)
WHERE `last_diagnostic_trace_json` IS NULL;

ALTER TABLE `fulfillment_record`
  MODIFY COLUMN `last_diagnostic_trace_json` JSON NOT NULL
    COMMENT 'Latest structured diagnostic trace for processing governance';

CREATE TABLE `fulfillment_attempt_record` (
  `attempt_id` VARCHAR(64) NOT NULL COMMENT 'Fulfillment attempt identifier',
  `fulfillment_id` VARCHAR(64) NOT NULL COMMENT 'Parent fulfillment identifier',
  `attempt_no` INT NOT NULL COMMENT 'Attempt sequence number; Stage A freezes to 1',
  `status` VARCHAR(32) NOT NULL COMMENT 'Attempt status: EXECUTING, SUCCEEDED, FAILED',
  `dispatcher_run_id` VARCHAR(64) NOT NULL COMMENT 'Dispatcher scan/run identifier that issued the claim',
  `executor_ref` VARCHAR(128) NOT NULL COMMENT 'Executor identity that holds the execution token',
  `execution_path` VARCHAR(64) NOT NULL COMMENT 'Execution path frozen to DEFAULT_PROVIDER in Stage A',
  `claimed_at` DATETIME(3) NOT NULL COMMENT 'Timestamp when claim won execution ownership',
  `started_at` DATETIME(3) DEFAULT NULL COMMENT 'Timestamp when attempt execution started',
  `finished_at` DATETIME(3) DEFAULT NULL COMMENT 'Timestamp when attempt reached a terminal state',
  `delivery_result_json` JSON DEFAULT NULL COMMENT 'Structured delivery result for SUCCEEDED attempts',
  `failure_json` JSON DEFAULT NULL COMMENT 'Structured failure summary for FAILED attempts',
  `diagnostic_trace_json` JSON DEFAULT NULL COMMENT 'Latest structured diagnostic trace for this attempt',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`attempt_id`),
  UNIQUE KEY `uk_fulfillment_attempt_record_fulfillment_attempt_no` (`fulfillment_id`, `attempt_no`),
  KEY `idx_fulfillment_attempt_record_status_claimed_at` (`status`, `claimed_at`)
) COMMENT='Fulfillment execution attempts for the Stage A processing backbone';
