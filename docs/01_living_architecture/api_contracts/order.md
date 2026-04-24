# API Contract — Order

**Maintained by:** librarian
**Last updated by:** migration (from RFC-TKT001-01 / RFC-TKT001-03 / `api-catalog.yaml`)
**Owning aggregate:** `TicketOrder`（参见 `domain_models.md §2.4`）
**Related state machine:** `global_state_machine.md §2`

---

## POST /orders

**Introduced by:** `feat-TKT001-01`
**Purpose:** 原子消费 `Reservation`（`ACTIVE → CONSUMED`）并创建 `TicketOrder.PENDING_PAYMENT`。

### Idempotency

* 必须携带 `Idempotency-Key` header。
* 幂等作用域：`action_name = CREATE_ORDER`，**不与** `POST /reservations` / `POST /payments/confirmations` 共享。
* 同 key 再到达：按 `idempotency_record.status` 判定（`SUCCEEDED` 回放、`PROCESSING` 返回 in-progress、`request_hash` 不一致返回 `IDEMPOTENCY_CONFLICT`）。

### Request Headers

| Name | Type | Required | Description |
|:--|:--|:--|:--|
| `Idempotency-Key` | string | yes | 动作级幂等键 |

### Request Body — `create-order-request`

```json
{
  "$id": "create-order-request",
  "type": "object",
  "required": ["external_trade_no", "reservation_id", "buyer"],
  "properties": {
    "external_trade_no": { "type": "string", "minLength": 1 },
    "reservation_id":    { "type": "string", "minLength": 1 },
    "buyer": {
      "type": "object",
      "required": ["buyer_ref"],
      "properties": {
        "buyer_ref":     { "type": "string", "minLength": 1 },
        "contact_phone": { "type": "string" },
        "contact_email": { "type": "string" }
      }
    },
    "submission_context": { "type": "object", "additionalProperties": { "type": "string" } }
  }
}
```

**Field notes**

* `external_trade_no` 必须与 `reservation` 创建时一致；服务端需校验两者同属一笔交易。
* `buyer` 原样持久化到 `ticket_order` 的 `buyer_ref` / `contact_phone` / `contact_email`。
* `submission_context` 原样持久化到 `ticket_order.submission_context_json`。

### Response 201 — `create-order-response`

```json
{
  "$id": "create-order-response",
  "type": "object",
  "required": ["order_id", "external_trade_no", "reservation_id", "status", "payment_deadline_at"],
  "properties": {
    "order_id":            { "type": "string" },
    "external_trade_no":   { "type": "string" },
    "reservation_id":      { "type": "string" },
    "status":              { "type": "string", "enum": ["PENDING_PAYMENT"] },
    "payment_deadline_at": { "type": "string", "format": "date-time" }
  }
}
```

**Field notes**

* 首次创建返回 `status = PENDING_PAYMENT`；幂等回放时总是返回创建时的 `order_id` 与 `payment_deadline_at`。
* `payment_deadline_at` 为 Timeout Sweep 的扫描入口（参见 `feat-TKT001-03`）。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `RESERVATION_NOT_FOUND` | `reservation_id` 不存在 |
| 409 | `RESERVATION_ALREADY_CONSUMED` | `reservation.status = CONSUMED`（他单已消费） |
| 410 | `RESERVATION_EXPIRED` | `reservation.status = EXPIRED` 或 `expires_at` 已过 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 不同 `request_hash` |

详见 `_shared_error_codes.md`。

### Transaction Boundary

本接口对应 `global_state_machine.md §7 T1 ReservationConsume+OrderCreate`：

* 写：`reservation_record`（ACTIVE→CONSUMED via `version` CAS）+ `ticket_order`（insert）+ `idempotency_record`。
* 物理保护：`uk_ticket_order_reservation_id` + `uk_reservation_consumed_order_id` 保证同一 reservation 只对应一个 order。
* 审计事件：`RESERVATION_CONSUMED` + `ORDER_CREATED`（由 `feat-TKT001-03` 追加到本事务）。
