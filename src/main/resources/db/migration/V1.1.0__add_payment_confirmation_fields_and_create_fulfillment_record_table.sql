ALTER TABLE `ticket_order`
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL
    COMMENT 'Order status: PENDING_PAYMENT, CONFIRMED, CLOSED',
  ADD COLUMN `confirmed_at` DATETIME(3) DEFAULT NULL
    COMMENT 'Timestamp when payment confirmation moves the order into CONFIRMED'
    AFTER `payment_deadline_at`,
  ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0
    COMMENT 'Optimistic lock version for payment confirmation and future close transitions'
    AFTER `confirmed_at`;

CREATE TABLE `fulfillment_record` (
  `fulfillment_id` VARCHAR(64) NOT NULL COMMENT 'Fulfillment identifier',
  `order_id` VARCHAR(64) NOT NULL COMMENT 'Confirmed order identifier',
  `status` VARCHAR(32) NOT NULL COMMENT 'Fulfillment status: PENDING',
  `payment_provider` VARCHAR(64) NOT NULL COMMENT 'Payment provider that triggered fulfillment creation',
  `provider_event_id` VARCHAR(128) NOT NULL COMMENT 'Provider event identifier for callback traceability',
  `provider_payment_id` VARCHAR(128) DEFAULT NULL COMMENT 'Provider payment identifier when supplied by channel',
  `confirmed_at` DATETIME(3) NOT NULL COMMENT 'Business confirmation time supplied by payment callback',
  `channel_context_json` JSON DEFAULT NULL COMMENT 'Normalized channel context payload from payment confirmation request',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version reserved for future fulfillment transitions',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`fulfillment_id`),
  UNIQUE KEY `uk_fulfillment_record_order_id` (`order_id`)
) COMMENT='Fulfillment trigger table created by valid payment confirmation';
