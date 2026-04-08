# Schema Summary — Global Registry
**Maintained by:** governance-agent  
**Last updated by:** RFC-TKT001-02  
**Database:** MySQL 8, `ticket_core`

---

## catalog_item

**Purpose:** 渠道可见商品与底层库存资源的绑定关系。  
**Introduced by:** RFC-TKT001-01  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `catalog_item_id` | VARCHAR(64) | NO | — | PK | 渠道侧商品标识符 |
| `inventory_resource_id` | VARCHAR(64) | NO | — | UK `uk_catalog_item_inventory_resource_id` | 绑定的库存资源 ID |
| `name` | VARCHAR(128) | NO | — | — | 商品显示名称 |
| `status` | VARCHAR(32) | NO | — | — | `DRAFT` / `ACTIVE` / `OFF_SHELF` |
| `version` | BIGINT | NO | 0 | — | 乐观锁版本号 |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

---

## inventory_resource

**Purpose:** 库存资源总量、已预留量与并发控制基线。  
**Introduced by:** RFC-TKT001-01  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `inventory_resource_id` | VARCHAR(64) | NO | — | PK | 库存资源标识符 |
| `resource_code` | VARCHAR(64) | NO | — | UK `uk_inventory_resource_code` | 人类可读库存编码 |
| `total_quantity` | INT | NO | — | — | 总库存数量 |
| `reserved_quantity` | INT | NO | 0 | — | 当前已预留数量 |
| `status` | VARCHAR(32) | NO | — | — | `ACTIVE` / `FROZEN` / `OFFLINE` |
| `version` | BIGINT | NO | 0 | — | 乐观锁版本号（扣减核心字段） |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

**Application logic:** `sellable_quantity = total_quantity - reserved_quantity`（应用层投影，不存储）

---

## reservation_record

**Purpose:** Reservation 生命周期与 TTL 管理。  
**Introduced by:** RFC-TKT001-01  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `reservation_id` | VARCHAR(64) | NO | — | PK | Reservation 标识符 |
| `external_trade_no` | VARCHAR(64) | NO | — | — | 整笔交易关联键 |
| `catalog_item_id` | VARCHAR(64) | NO | — | — | 关联商品 ID |
| `inventory_resource_id` | VARCHAR(64) | NO | — | — | 关联库存资源 ID |
| `quantity` | INT | NO | — | — | 预留数量 |
| `status` | VARCHAR(32) | NO | — | — | `ACTIVE` / `CONSUMED` / `EXPIRED` |
| `expires_at` | DATETIME(3) | NO | — | — | 由 `reservation_ttl_seconds` 推导 |
| `consumed_order_id` | VARCHAR(64) | YES | NULL | UK `uk_reservation_consumed_order_id` | consume 时写入的 Order ID |
| `consumed_at` | DATETIME(3) | YES | NULL | — | consume 时间 |
| `channel_context_json` | JSON | YES | NULL | — | 渠道上下文 payload |
| `version` | BIGINT | NO | 0 | — | 乐观锁（保护 ACTIVE→CONSUMED 唯一性） |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

**Indexes (V1.0.1):** `idx_reservation_status_expires_at (status, expires_at)` — 超时扫描入口

---

## ticket_order

**Purpose:** 由 Reservation 派生的 Order 主记录，支付确认后可推进到 `CONFIRMED`。  
**Introduced by:** RFC-TKT001-01（RFC-TKT001-02 扩展）  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `order_id` | VARCHAR(64) | NO | — | PK | Order 标识符 |
| `external_trade_no` | VARCHAR(64) | NO | — | UK `uk_ticket_order_external_trade_no` | 整笔交易关联键 |
| `reservation_id` | VARCHAR(64) | NO | — | UK `uk_ticket_order_reservation_id` | 物理保证一个 Reservation 只落一个 Order |
| `status` | VARCHAR(32) | NO | — | — | `PENDING_PAYMENT` / `CONFIRMED` / `CLOSED` |
| `buyer_ref` | VARCHAR(64) | NO | — | — | 来自上游渠道的购买方标识 |
| `contact_phone` | VARCHAR(32) | YES | NULL | — | 购买方联系电话 |
| `contact_email` | VARCHAR(128) | YES | NULL | — | 购买方联系邮箱 |
| `submission_context_json` | JSON | YES | NULL | — | 下单上下文 payload |
| `payment_deadline_at` | DATETIME(3) | NO | — | — | 支付截止时间（超时关闭扫描入口） |
| `confirmed_at` | DATETIME(3) | YES | NULL | — | 支付确认落单时间，进入 `CONFIRMED` 时写入 |
| `version` | BIGINT | NO | 0 | — | 乐观锁版本号，保护支付确认与后续关闭竞争 |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

---

## fulfillment_record

**Purpose:** Payment Confirmation 成功后创建的履约投影，作为后续履约执行的唯一稳定起点。  
**Introduced by:** RFC-TKT001-02  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `fulfillment_id` | VARCHAR(64) | NO | — | PK | Fulfillment 标识符 |
| `order_id` | VARCHAR(64) | NO | — | UK `uk_fulfillment_record_order_id` | 物理保证一个 Order 只生成一个 Fulfillment |
| `status` | VARCHAR(32) | NO | — | — | 当前固定为 `PENDING` |
| `payment_provider` | VARCHAR(64) | NO | — | — | 触发确认的支付渠道 |
| `provider_event_id` | VARCHAR(128) | NO | — | — | 渠道事件 ID，用于回调排障 |
| `provider_payment_id` | VARCHAR(128) | YES | NULL | — | 渠道支付单号，若上游提供则落库 |
| `confirmed_at` | DATETIME(3) | NO | — | — | 业务确认时间，来自支付回调 |
| `channel_context_json` | JSON | YES | NULL | — | 标准化后的渠道上下文 payload |
| `version` | BIGINT | NO | 0 | — | 预留给后续履约状态迁移的乐观锁字段 |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

---

## idempotency_record

**Purpose:** 动作级幂等命中结果缓存与冲突检测。  
**Introduced by:** RFC-TKT001-01  

| Column | Type | Nullable | Default | Constraints | Notes |
|:--|:--|:--|:--|:--|:--|
| `idempotency_record_id` | VARCHAR(64) | NO | — | PK | 幂等记录标识符 |
| `action_name` | VARCHAR(64) | NO | — | UK(action_name, idempotency_key) | 如 `CREATE_RESERVATION` / `CREATE_ORDER` |
| `idempotency_key` | VARCHAR(128) | NO | — | UK(action_name, idempotency_key) | 客户端提供的 Idempotency-Key |
| `request_hash` | VARCHAR(128) | NO | — | — | 请求体 SHA-256 摘要，用于冲突检测 |
| `external_trade_no` | VARCHAR(64) | NO | — | — | 整笔交易关联键 |
| `resource_type` | VARCHAR(32) | YES | NULL | — | 业务资源类型（SUCCEEDED 后写入） |
| `resource_id` | VARCHAR(64) | YES | NULL | — | 业务资源 ID（SUCCEEDED 后写入） |
| `status` | VARCHAR(32) | NO | — | — | `PROCESSING` / `SUCCEEDED` / `FAILED` |
| `response_payload` | JSON | YES | NULL | — | 缓存的成功响应体，用于幂等回放 |
| `created_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | — | 创建时间 |
| `updated_at` | DATETIME(3) | NO | CURRENT_TIMESTAMP(3) | ON UPDATE | 最后更新时间 |

**注意 (V1.0.1):** `resource_type` 已从 NOT NULL 变更为 DEFAULT NULL，以允许 PROCESSING 状态记录在 resource_type 确定前插入。
