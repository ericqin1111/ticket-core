# API Contract — Reservation

**Maintained by:** librarian
**Last updated by:** migration (from RFC-TKT001-01 / `api-catalog.yaml`)
**Owning aggregate:** `Reservation`（参见 `domain_models.md §2.3`）
**Related state machine:** `global_state_machine.md §1`

---

## POST /reservations

**Introduced by:** `feat-TKT001-01`
**Purpose:** 创建 `Reservation.ACTIVE` 并占用对应 `InventoryResource.reserved_quantity`。

### Idempotency

* 必须携带 `Idempotency-Key` header。
* 幂等作用域：`action_name = CREATE_RESERVATION`，**不与** `POST /orders` / `POST /payments/confirmations` 共享。
* 同 key 再到达：
  * `SUCCEEDED` — 回放 `response_payload`。
  * `PROCESSING` — 返回 `PAYMENT_CONFIRMATION_IN_PROGRESS` 的同形式（此接口尚无专用 `_IN_PROGRESS` 码，由 feat 后续补齐或复用）。
  * 不同 `request_hash` — 返回 `IDEMPOTENCY_CONFLICT`。

### Request Headers

| Name | Type | Required | Description |
|:--|:--|:--|:--|
| `Idempotency-Key` | string | yes | 动作级幂等键 |

### Request Body — `create-reservation-request`

```json
{
  "$id": "create-reservation-request",
  "type": "object",
  "required": ["external_trade_no", "catalog_item_id", "quantity", "reservation_ttl_seconds"],
  "properties": {
    "external_trade_no":       { "type": "string", "minLength": 1 },
    "catalog_item_id":         { "type": "string", "minLength": 1 },
    "quantity":                { "type": "integer", "minimum": 1 },
    "reservation_ttl_seconds": { "type": "integer", "minimum": 30 },
    "channel_context":         { "type": "object", "additionalProperties": { "type": "string" } }
  }
}
```

**Field notes**

* `external_trade_no` 为整笔交易的关联键，后续 `POST /orders` / `POST /payments/confirmations` 必须携带同一值。
* `reservation_ttl_seconds` 是原生 TTL；超过后 `ACTIVE → EXPIRED`，不依赖下游 Order。
* `channel_context` 为渠道上下文，原样持久化到 `reservation_record.channel_context_json`。

### Response 201 — `create-reservation-response`

```json
{
  "$id": "create-reservation-response",
  "type": "object",
  "required": ["reservation_id", "external_trade_no", "catalog_item_id", "quantity", "status", "expires_at"],
  "properties": {
    "reservation_id":    { "type": "string" },
    "external_trade_no": { "type": "string" },
    "catalog_item_id":   { "type": "string" },
    "quantity":          { "type": "integer", "minimum": 1 },
    "status":            { "type": "string", "enum": ["ACTIVE", "CONSUMED", "EXPIRED"] },
    "expires_at":        { "type": "string", "format": "date-time" }
  }
}
```

**Field notes**

* 首次成功时 `status = ACTIVE`；幂等回放时按当时快照返回（可能已是 `CONSUMED` / `EXPIRED`）。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 422 | `CATALOG_ITEM_NOT_SELLABLE` | 商品不存在或 `status != ACTIVE` |
| 409 | `INSUFFICIENT_INVENTORY` | 可售量不足或 `inventory.version` CAS 失配（retryable） |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 不同 `request_hash` |

详见 `_shared_error_codes.md`。

### Transaction Boundary

本接口自身只写 `reservation_record` + `inventory_resource` + `idempotency_record`，属于轻量单聚合事务。`ACTIVE → CONSUMED` 不在本事务，由 `POST /orders` 承接（参见 `order.md`）。
