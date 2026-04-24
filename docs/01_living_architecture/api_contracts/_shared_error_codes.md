# Shared Error Codes

**Maintained by:** librarian
**Last updated by:** migration (from `docs/01_registries/api-catalog.yaml`, RFC-TKT001-01/02/03)

所有模块的业务错误码共用 `ErrorResponse` 结构。任何新错误码的引入必须由 feat 的 Architect Stage 提案，经 Reviewer 批准后登记到本文件。

---

## ErrorResponse Envelope

```json
{
  "$id": "error-response",
  "type": "object",
  "required": ["code", "message", "request_id", "retryable"],
  "properties": {
    "code":        { "type": "string" },
    "message":     { "type": "string" },
    "request_id":  { "type": "string" },
    "retryable":   { "type": "boolean" }
  }
}
```

* `code`：稳定业务码，跨版本禁止复用语义。
* `retryable`：用于指导客户端是否可安全重试；即使 `retryable=true`，也必须重复原 `Idempotency-Key`。

---

## Error Code Registry

| Code | HTTP | retryable | 来源 | 语义 |
|:--|:--|:--|:--|:--|
| `CATALOG_ITEM_NOT_SELLABLE` | 422 | false | feat-TKT001-01 | 商品不存在或 `status != ACTIVE` |
| `INSUFFICIENT_INVENTORY` | 409 | true | feat-TKT001-01 | 当前可售量不足或并发冲突 |
| `RESERVATION_NOT_FOUND` | 404 | false | feat-TKT001-01 | `reservation_id` 不存在 |
| `RESERVATION_ALREADY_CONSUMED` | 409 | false | feat-TKT001-01 | `reservation.status = CONSUMED`，无法再次消费 |
| `RESERVATION_EXPIRED` | 410 | false | feat-TKT001-01 | `reservation.status = EXPIRED` 或 `expires_at` 已过 |
| `ORDER_NOT_FOUND` | 404 | false | feat-TKT001-02 | `external_trade_no` 无法定位到订单 |
| `ORDER_NOT_CONFIRMABLE` | 409 | false | feat-TKT001-02 / feat-TKT001-03 | 订单状态不是 `PENDING_PAYMENT`（已 `CONFIRMED`、已 `CLOSED` 或其他不可确认状态） |
| `PAYMENT_CONFIRMATION_IN_PROGRESS` | 409 | true | feat-TKT001-02 | 同 key 并发确认进行中，客户端可携同 key 重试 |
| `FULFILLMENT_INVARIANT_BROKEN` | 409 | false | feat-TKT001-02 | `Order.CONFIRMED` 却无稳定 Fulfillment 投影，需人工介入 |
| `IDEMPOTENCY_CONFLICT` | 409 | false | feat-TKT001-01 | 同 `Idempotency-Key` + 不同 `request_hash` |

---

## Retry Semantics

| retryable | 客户端建议 |
|:--|:--|
| `true` | 保留原 `Idempotency-Key` 重试；若接口幂等作用域内已有 SUCCEEDED 记录，将回放成功响应 |
| `false` | 业务终态，重试无意义；客户端必须处理该错误而非盲重 |

---

## Provenance

* `feat-TKT001-01`：引入 `CATALOG_ITEM_NOT_SELLABLE` / `INSUFFICIENT_INVENTORY` / `RESERVATION_*` / `IDEMPOTENCY_CONFLICT`。
* `feat-TKT001-02`：引入 `ORDER_NOT_FOUND` / `ORDER_NOT_CONFIRMABLE` / `PAYMENT_CONFIRMATION_IN_PROGRESS` / `FULFILLMENT_INVARIANT_BROKEN`。
* `feat-TKT001-03`：复用 `ORDER_NOT_CONFIRMABLE` 作为 timeout close 后的稳定拒绝码；新增 `PAYMENT_CONFIRMATION_REJECTED` 为**审计事件类型**而非 HTTP error code。
