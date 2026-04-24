# API Contract — Audit Trail

**Maintained by:** librarian
**Last updated by:** migration (from RFC-TKT001-03)
**Owning aggregate:** `AuditTrailEvent`（参见 `domain_models.md §2.6`）

---

## 概述

Phase 1 的 Audit Trail 只冻结 **append-only 内部 contract**，不暴露 public API。所有关键业务事件必须在其**业务主状态同一本地事务内** append 一条 `audit_trail_event`。

* 不支持 update / delete / reopen；任何"纠正"必须以新事件追加（例如 `RESERVATION_RELEASED` after `RESERVATION_CONSUMED`）。
* 不原样持久化敏感 payload；只保留关联键、`reason_code`、结构化 `payload_summary`。

---

## AuditTrailEvent Contract

```json
{
  "$id": "audit-trail-event",
  "type": "object",
  "required": [
    "event_id",
    "event_type",
    "external_trade_no",
    "occurred_at",
    "source_module"
  ],
  "properties": {
    "event_id":          { "type": "string", "minLength": 1 },
    "event_type":        { "type": "string" },
    "external_trade_no": { "type": "string", "minLength": 1 },
    "occurred_at":       { "type": "string", "format": "date-time" },
    "source_module":     { "type": "string" },

    "order_id":          { "type": "string" },
    "reservation_id":    { "type": "string" },
    "fulfillment_id":    { "type": "string" },
    "inventory_resource_id": { "type": "string" },
    "provider_event_id": { "type": "string" },

    "reason_code":       { "type": "string" },
    "payload_summary":   { "type": "object", "additionalProperties": true }
  }
}
```

**Field notes**

* `external_trade_no` 是跨聚合关联的主轴；任何 event 必须携带。
* 至少携带目标聚合主键之一（`order_id` / `reservation_id` / `fulfillment_id` / `inventory_resource_id`）。
* `provider_event_id` 仅在由支付回调触发时出现，用于排障。
* `reason_code` 用于区分同一 `event_type` 的多种业务原因（例如 `RESERVATION_RELEASED` 同时覆盖原生 TTL 与 `ORDER_PAYMENT_TIMEOUT`）。

---

## Registered Event Types

| Event Type | 触发时机 | 关联事务 | 必填关联键 | 首次引入 |
|:--|:--|:--|:--|:--|
| `RESERVATION_CREATED` | `POST /reservations` 成功 | 轻量 reservation 事务 | `reservation_id` | feat-TKT001-03 (补录) |
| `RESERVATION_CONSUMED` | `POST /orders` 成功 | T1 | `reservation_id`, `order_id` | feat-TKT001-03 (补录) |
| `RESERVATION_RELEASED` | TTL 过期 **或** Order timeout close | 扫描事务 / T3 | `reservation_id`, `reason_code` | feat-TKT001-03 |
| `ORDER_CREATED` | `POST /orders` 成功 | T1 | `order_id`, `reservation_id` | feat-TKT001-03 (补录) |
| `ORDER_CONFIRMED` | `POST /payments/confirmations` 胜出 | T2 | `order_id`, `provider_event_id` | feat-TKT001-03 (补录) |
| `ORDER_TIMEOUT_CLOSED` | Timeout Sweep 胜出 | T3 | `order_id`, `reservation_id` | feat-TKT001-03 |
| `PAYMENT_CONFIRMATION_APPLIED` | Payment Confirmation 首次成功 | T2 | `order_id`, `provider_event_id` | feat-TKT001-03 (补录) |
| `PAYMENT_CONFIRMATION_REPLAYED` | Payment Confirmation 幂等回放 | — | `order_id`, `provider_event_id` | feat-TKT001-03 (补录) |
| `PAYMENT_CONFIRMATION_REJECTED` | Payment Confirmation 到达时订单已 `CLOSED` / 不可确认 | 拒绝事务 | `order_id`, `provider_event_id`, `reason_code` | feat-TKT001-03 |
| `FULFILLMENT_CREATED` | `Fulfillment.PENDING` 创建 | T2 | `order_id`, `fulfillment_id` | feat-TKT001-03 (补录) |
| `INVENTORY_RESTORED` | Order timeout close 时库存回补 | T3 | `inventory_resource_id`, `reservation_id` | feat-TKT001-03 |

---

## 事务承诺

* Audit append 与业务主状态**同事务提交**；任何一方回滚，另一方必须回滚。
* 不允许"主状态成功、审计缺失"；不允许"审计成功、主状态未提交"。
* `audit_trail_event` 只 append，不 update、不 delete；违反即视为治理事故。

---

## 结构化日志字段（非 audit，但并行输出）

每条 append 必须同时打结构化日志，字段至少包含：

`scan_id` / `request_id` / `external_trade_no` / `order_id` / `reservation_id` / `inventory_resource_id` / `provider_event_id` / `decision` / `reason_code`

日志是排障路径；audit 是治理路径。两者独立但同源。
