# API Contract — Payment

**Maintained by:** librarian
**Last updated by:** migration (from RFC-TKT001-02 / RFC-TKT001-03 / `api-catalog.yaml`)
**Owning aggregate:** `TicketOrder` + `Fulfillment`（参见 `domain_models.md §2.4 / §2.5`）
**Related state machine:** `global_state_machine.md §2 / §3 / §6`

---

## POST /payments/confirmations

**Introduced by:** `feat-TKT001-02`（`feat-TKT001-03` 追加 timeout close 后的拒绝语义）
**Purpose:** 接收支付渠道异步确认。在同一本地事务内原子推进 `TicketOrder.PENDING_PAYMENT → CONFIRMED` 并创建唯一 `Fulfillment.PENDING`。

### Idempotency

* 必须携带 `Idempotency-Key` header。
* 幂等作用域：`action_name = PAYMENT_CONFIRMATION`，**不与** 其他 POST 接口共享。
* 幂等命中顺序：
  1. 先按 `(action_name, idempotency_key)` 命中 `idempotency_record`。
  2. 若未命中，按 `external_trade_no` 检查目标 `Order` 是否已 `CONFIRMED` 且已有唯一 Fulfillment；若成立，返回稳定成功投影（`payment_confirmation_status = REPLAYED`）。
* 同 key 不同 `request_hash` → `IDEMPOTENCY_CONFLICT`。

### Request Headers

| Name | Type | Required | Description |
|:--|:--|:--|:--|
| `Idempotency-Key` | string | yes | Payment Confirmation 动作级幂等键 |

### Request Body — `payment-confirmation-request`

```json
{
  "$id": "payment-confirmation-request",
  "type": "object",
  "required": ["external_trade_no", "payment_provider", "provider_event_id", "confirmed_at"],
  "properties": {
    "external_trade_no":   { "type": "string", "minLength": 1 },
    "payment_provider":    { "type": "string", "minLength": 1 },
    "provider_event_id":   { "type": "string", "minLength": 1 },
    "provider_payment_id": { "type": "string" },
    "confirmed_at":        { "type": "string", "format": "date-time" },
    "channel_context":     { "type": "object", "additionalProperties": { "type": "string" } }
  }
}
```

**Field notes**

* `provider_event_id` 仅用于排障关联；不对其加唯一约束（同一渠道事件允许被再次发送）。
* `confirmed_at` 为业务确认时间，由渠道侧给出；与服务端事务提交时间分离。

### Response 200 — `payment-confirmation-response`

```json
{
  "$id": "payment-confirmation-response",
  "type": "object",
  "required": [
    "order_id",
    "external_trade_no",
    "order_status",
    "fulfillment_id",
    "fulfillment_status",
    "payment_confirmation_status",
    "confirmed_at"
  ],
  "properties": {
    "order_id":                    { "type": "string" },
    "external_trade_no":           { "type": "string" },
    "order_status":                { "type": "string", "enum": ["CONFIRMED"] },
    "fulfillment_id":              { "type": "string" },
    "fulfillment_status":          { "type": "string", "enum": ["PENDING"] },
    "payment_confirmation_status": { "type": "string", "enum": ["APPLIED", "REPLAYED"] },
    "confirmed_at":                { "type": "string", "format": "date-time" }
  }
}
```

**Field notes**

* `payment_confirmation_status`：
  * `APPLIED` — 本次请求首次完成业务事务。
  * `REPLAYED` — 幂等回放（动作级或 `external_trade_no` 级稳定投影命中）。
* 同一 `Order` 的多次成功响应必须返回相同 `fulfillment_id`。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `ORDER_NOT_FOUND` | `external_trade_no` 无法定位到订单 |
| 409 | `ORDER_NOT_CONFIRMABLE` | `Order.status ∈ {CONFIRMED（旧）, CLOSED}` 或其他不可确认状态。Timeout close 后的 late payment 走此码。 |
| 409 | `PAYMENT_CONFIRMATION_IN_PROGRESS` | 同 key 并发进行中，retryable |
| 409 | `FULFILLMENT_INVARIANT_BROKEN` | `Order.CONFIRMED` 却无稳定 Fulfillment 投影（数据损坏，需人工介入） |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 不同 `request_hash` |

详见 `_shared_error_codes.md`。

### Transaction Boundary

本接口对应 `global_state_machine.md §7 T2 PaymentConfirmation`：

* 写：`ticket_order`（PENDING_PAYMENT → CONFIRMED via `version` CAS）+ `fulfillment_record`（insert，受 `uk_fulfillment_record_order_id` 保护）+ `audit_trail_event`（`ORDER_CONFIRMED` / `FULFILLMENT_CREATED` / `PAYMENT_CONFIRMATION_APPLIED`）+ `idempotency_record`。
* 并发裁决：与 Timeout Sweep 竞争同一 `Order.PENDING_PAYMENT`，败者返回 `ORDER_NOT_CONFIRMABLE` 并 append `PAYMENT_CONFIRMATION_REJECTED`（参见 `global_state_machine.md §6`）。
* 安全前置：channel gateway 负责 callback 签名校验与 payload 标准化；本接口假定进入时已完成安全前置。
