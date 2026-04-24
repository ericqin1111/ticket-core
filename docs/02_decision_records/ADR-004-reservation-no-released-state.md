# ADR-004: Reservation State: No RELEASED Status, Reason by Audit Code

**Status:** Accepted  
**Context:** RFC-TKT001-01 / RFC-TKT001-03  
**Date:** 2026-04-23

## Problem

Reservation 从 `ACTIVE` 可释放，原因多样：
- 原生 TTL 过期（用户没抢到）
- Order timeout close（锁单了但无人支付）

引入新 `RELEASED` 状态会导致：
- 状态枚举爆炸（ACTIVE → RELEASED → EXPIRED？还是 ACTIVE → RELEASED?）。
- 后续查询复杂："已释放"和"已过期"的语义区别不清。

## Decision

**不引入 `RELEASED` 状态。** 统一收敛到 `EXPIRED`：

```
ACTIVE
  → CONSUMED（成功消费）
  → EXPIRED（释放，原因由 AuditTrailEvent.reason_code 区分）
     - reason_code = 'TTL_EXCEEDED'（原生过期）
     - reason_code = 'ORDER_PAYMENT_TIMEOUT'（Order 超时释放）
```

## Rationale

1. **状态机简洁：** 只有 3 个状态（ACTIVE / CONSUMED / EXPIRED），易推理。
2. **查询友好：** 业务查询 `SELECT * FROM reservation WHERE status='EXPIRED'` 统一覆盖所有释放场景；进阶查询才按 audit trail 的 `reason_code` 分析。
3. **扩展友好：** 后续新的释放原因（例如"库存冻结"）无需改表结构，只需新增 `reason_code`。

## Consequences

### Positive
- 状态简洁，易测试。
- reason_code 灵活扩展。

### Negative
- 原因查询需关联 `audit_trail_event` 表，不能仅从 `reservation_record` 字段判定。改进方案：若性能瓶颈，后续可在 `reservation_record` 冗余 `release_reason_code` 字段。

## Related Files
- `global_state_machine.md` §1（Reservation 状态图）
- `api_contracts/audit.md`（RESERVATION_RELEASED event + reason_code）
- `domain_models.md` §2.3（Reservation 聚合定义）
