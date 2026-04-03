CREATE TABLE `catalog_item` (
  `catalog_item_id` VARCHAR(64) NOT NULL COMMENT 'Channel-facing catalog item identifier',
  `inventory_resource_id` VARCHAR(64) NOT NULL COMMENT 'Bound inventory resource identifier',
  `name` VARCHAR(128) NOT NULL COMMENT 'Catalog item display name',
  `status` VARCHAR(32) NOT NULL COMMENT 'Catalog item status: DRAFT, ACTIVE, OFF_SHELF',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version for catalog updates',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`catalog_item_id`),
  UNIQUE KEY `uk_catalog_item_inventory_resource_id` (`inventory_resource_id`)
) COMMENT='Catalog item to inventory binding table';

CREATE TABLE `inventory_resource` (
  `inventory_resource_id` VARCHAR(64) NOT NULL COMMENT 'Inventory resource identifier',
  `resource_code` VARCHAR(64) NOT NULL COMMENT 'Human-readable inventory resource code',
  `total_quantity` INT NOT NULL COMMENT 'Total quantity available for this inventory resource',
  `reserved_quantity` INT NOT NULL DEFAULT 0 COMMENT 'Currently reserved quantity',
  `status` VARCHAR(32) NOT NULL COMMENT 'Inventory resource status: ACTIVE, FROZEN, OFFLINE',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version for strong consistency updates',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`inventory_resource_id`),
  UNIQUE KEY `uk_inventory_resource_code` (`resource_code`)
) COMMENT='Inventory resource master table';

CREATE TABLE `reservation_record` (
  `reservation_id` VARCHAR(64) NOT NULL COMMENT 'Reservation identifier',
  `external_trade_no` VARCHAR(64) NOT NULL COMMENT 'External trade number linking the full transaction',
  `catalog_item_id` VARCHAR(64) NOT NULL COMMENT 'Referenced catalog item identifier',
  `inventory_resource_id` VARCHAR(64) NOT NULL COMMENT 'Referenced inventory resource identifier',
  `quantity` INT NOT NULL COMMENT 'Reserved quantity',
  `status` VARCHAR(32) NOT NULL COMMENT 'Reservation status: ACTIVE, CONSUMED, EXPIRED',
  `expires_at` DATETIME(3) NOT NULL COMMENT 'Reservation expiration time',
  `consumed_order_id` VARCHAR(64) DEFAULT NULL COMMENT 'Order identifier that consumed this reservation',
  `consumed_at` DATETIME(3) DEFAULT NULL COMMENT 'Reservation consumption time',
  `channel_context_json` JSON DEFAULT NULL COMMENT 'Channel context payload from reservation request',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version for reservation state transition',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`reservation_id`),
  UNIQUE KEY `uk_reservation_consumed_order_id` (`consumed_order_id`)
) COMMENT='Reservation lifecycle table';

CREATE TABLE `ticket_order` (
  `order_id` VARCHAR(64) NOT NULL COMMENT 'Order identifier',
  `external_trade_no` VARCHAR(64) NOT NULL COMMENT 'External trade number linking the full transaction',
  `reservation_id` VARCHAR(64) NOT NULL COMMENT 'Consumed reservation identifier',
  `status` VARCHAR(32) NOT NULL COMMENT 'Order status: PENDING_PAYMENT',
  `buyer_ref` VARCHAR(64) NOT NULL COMMENT 'Buyer reference from upstream channel',
  `contact_phone` VARCHAR(32) DEFAULT NULL COMMENT 'Buyer contact phone number',
  `contact_email` VARCHAR(128) DEFAULT NULL COMMENT 'Buyer contact email',
  `submission_context_json` JSON DEFAULT NULL COMMENT 'Submission context payload from order request',
  `payment_deadline_at` DATETIME(3) NOT NULL COMMENT 'Deadline for payment before future timeout close',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_ticket_order_external_trade_no` (`external_trade_no`),
  UNIQUE KEY `uk_ticket_order_reservation_id` (`reservation_id`)
) COMMENT='Order backbone table created from reservation consume';

CREATE TABLE `idempotency_record` (
  `idempotency_record_id` VARCHAR(64) NOT NULL COMMENT 'Idempotency record identifier',
  `action_name` VARCHAR(64) NOT NULL COMMENT 'Action name, such as CREATE_RESERVATION or CREATE_ORDER',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT 'Client supplied Idempotency-Key',
  `request_hash` VARCHAR(128) NOT NULL COMMENT 'Hash of canonical request payload for conflict detection',
  `external_trade_no` VARCHAR(64) NOT NULL COMMENT 'External trade number linked to the action',
  `resource_type` VARCHAR(32) NOT NULL COMMENT 'Business resource type returned by this action',
  `resource_id` VARCHAR(64) DEFAULT NULL COMMENT 'Business resource identifier returned by this action',
  `status` VARCHAR(32) NOT NULL COMMENT 'Idempotency processing status: PROCESSING, SUCCEEDED, FAILED',
  `response_payload` JSON DEFAULT NULL COMMENT 'Cached response payload for idempotent replay',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Record creation time',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Record last update time',
  PRIMARY KEY (`idempotency_record_id`),
  UNIQUE KEY `uk_idempotency_record_action_key` (`action_name`, `idempotency_key`)
) COMMENT='Action-scoped idempotency record table';
