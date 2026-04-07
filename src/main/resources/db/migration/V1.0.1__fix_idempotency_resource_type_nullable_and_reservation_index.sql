-- BLOCKER-01: resource_type must allow NULL so that PROCESSING records can be inserted
--   without a value (resource_type is only set upon SUCCEEDED).
ALTER TABLE `idempotency_record`
  MODIFY COLUMN `resource_type` VARCHAR(32) DEFAULT NULL
    COMMENT 'Business resource type returned by this action, set upon SUCCEEDED';

-- WARNING-03: Add composite index to support future timeout-close scan on reservation_record.
--   RFC Section 4.3.3 names expires_at as the scan entry point; without this index the scan
--   would require a full table scan.
CREATE INDEX idx_reservation_status_expires_at
  ON reservation_record (status, expires_at);
