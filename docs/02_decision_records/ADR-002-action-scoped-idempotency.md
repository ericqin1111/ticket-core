# ADR-002: Action-Scoped Idempotency (Separate Key Spaces)

**Status:** Accepted  
**Context:** RFC-TKT001-01 / RFC-TKT001-02  
**Date:** 2026-04-23

## Problem

不同业务动作（创建 Reservation、创建 Order、支付确认）可能来自同一 `external_trade_no`，但语义完全不同。若全局共享一个幂等键空间，会导致：
- `POST /reservations` 返回幂等结果后，客户端用同一 key 调 `POST /orders`，误击未预期的缓存。
- 难以区分"已成功"vs"进行中"vs"冲突"的真实语义。

## Decision

幂等键按**动作级别**隔离：

| 动作 | Idempotency-Key | 幂等记录表 uk | 作用域 |
|:--|:--|:--|:--|
| 创建 Reservation | `header: Idempotency-Key` | `(action_name='CREATE_RESERVATION', idempotency_key)` | 仅限 POST /reservations |
| 创建 Order | `header: Idempotency-Key` | `(action_name='CREATE_ORDER', idempotency_key)` | 仅限 POST /orders |
| 支付确认 | `header: Idempotency-Key` | `(action_name='PAYMENT_CONFIRMATION', idempotency_key)` | 仅限 POST /payments/confirmations |

同一 `Idempotency-Key` 值可在三个不同动作空间中各现一次，互不冲突。

## Rationale

1. **语义清晰：** 每个 key 明确绑定到一个业务动作，不会误触。
2. **可扩展：** 新业务动作（例如"后续履约"）可直接引入新 `action_name`，无需协商 key 格式。
3. **幂等快速路径：** 可同时支持动作级幂等 + `external_trade_no` 级稳定投影：
   - 先按 `(action_name, key)` 命中幂等记录。
   - 不命中时，按业务快照判定（例如 Payment Confirmation 检查 "order is CONFIRMED && fulfillment exists"）。

## Consequences

### Positive
- 客户端使用相同 key 值在不同接口无碍。
- Idempotency 记录紧凑，易于 cleanup。

### Negative
- 服务端需文档化每个接口要求的 `action_name`，客户端感知幂等空间隔离。

## Related Files
- `api_contracts/reservation.md`（action_name = CREATE_RESERVATION）
- `api_contracts/order.md`（action_name = CREATE_ORDER）
- `api_contracts/payment.md`（action_name = PAYMENT_CONFIRMATION）
- `schema_summary.md`（idempotency_record table uk 定义）
