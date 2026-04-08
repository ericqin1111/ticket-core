ALTER TABLE `ticket_order`
  ADD COLUMN `closed_at` DATETIME(3) DEFAULT NULL
    COMMENT 'Timestamp when timeout sweep moves the order into CLOSED'
    AFTER `confirmed_at`;

ALTER TABLE `reservation_record`
  ADD COLUMN `released_at` DATETIME(3) DEFAULT NULL
    COMMENT 'Timestamp when a consumed reservation is released by order timeout close'
    AFTER `consumed_at`;

CREATE TABLE `audit_trail_event` (
  `event_id` VARCHAR(64) NOT NULL COMMENT 'Audit event identifier',
  `event_type` VARCHAR(64) NOT NULL COMMENT 'Audit event type such as ORDER_TIMEOUT_CLOSED or PAYMENT_CONFIRMATION_REJECTED',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT 'Aggregate type: RESERVATION, ORDER, PAYMENT_CONFIRMATION, FULFILLMENT, INVENTORY_RESOURCE',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT 'Aggregate identifier referenced by this audit event',
  `external_trade_no` VARCHAR(64) NOT NULL COMMENT 'External trade number linking the full transaction',
  `order_id` VARCHAR(64) DEFAULT NULL COMMENT 'Related order identifier when applicable',
  `reservation_id` VARCHAR(64) DEFAULT NULL COMMENT 'Related reservation identifier when applicable',
  `inventory_resource_id` VARCHAR(64) DEFAULT NULL COMMENT 'Related inventory resource identifier when applicable',
  `fulfillment_id` VARCHAR(64) DEFAULT NULL COMMENT 'Related fulfillment identifier when applicable',
  `provider_event_id` VARCHAR(128) DEFAULT NULL COMMENT 'Provider event identifier when the event is callback-driven',
  `actor_type` VARCHAR(32) NOT NULL COMMENT 'Actor type: SYSTEM, CHANNEL, OPERATOR',
  `actor_ref` VARCHAR(128) DEFAULT NULL COMMENT 'Actor reference such as scheduler id, channel name, or operator id',
  `request_id` VARCHAR(64) DEFAULT NULL COMMENT 'Request identifier for distributed traceability',
  `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT 'Idempotency-Key associated with the triggering action when applicable',
  `reason_code` VARCHAR(64) NOT NULL COMMENT 'Reason code such as ORDER_PAYMENT_TIMEOUT or CLOSED_BY_TIMEOUT',
  `payload_summary_json` JSON DEFAULT NULL COMMENT 'Minimal non-sensitive payload summary for audit traceability',
  `occurred_at` DATETIME(3) NOT NULL COMMENT 'Business occurrence time of the audited event',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Persistence time of the audit row',
  PRIMARY KEY (`event_id`)
) COMMENT='Append-only audit trail event table for transaction lifecycle changes';
