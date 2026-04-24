# ADR-006: Audit Trail Append-Only + Single Transaction Consistency

**Status:** Accepted  
**Context:** RFC-TKT001-03  
**Date:** 2026-04-23

## Problem

Audit Trail 既需保证**不可篡改性**（审计需求），又需保证**与业务主状态的一致性**（数据治理需求）。

常见错误设计：
- Audit 异步 append：主状态成功，audit 失败 → 违反一致性。
- Audit 另起事务：rollback 前读 audit，看到主状态未成功的 audit 记录 → 混乱。

## Decision

1. **Audit append 与业务主状态同事务提交**
   ```sql
   BEGIN TRANSACTION;
     UPDATE ticket_order SET status = 'CONFIRMED' WHERE ...;
     INSERT INTO audit_trail_event (...) VALUES (...);
   COMMIT;
   ```
   一个成功，另一个必成功；任何一方 rollback，双方都 rollback。

2. **Audit append-only，禁止 update/delete**
   - 任何"纠正"必须以新事件追加，不覆盖历史。
   - Example：若错误 append 了 `ORDER_CONFIRMED`，应再 append `ORDER_CONFIRMED_RETRACTED`；而非删除或修改前一条。

3. **Audit Trail 只记最小契约**
   - 关联键（external_trade_no, order_id 等）、reason_code、payload_summary。
   - 不原样存储敏感信息（例如支付卡号 payload）。

## Rationale

1. **事务一致性：** 业务快照与审计快照同步，无中间态暴露。
2. **不可否认性：** append-only 禁止事后修改，符合审计需求。
3. **故障恢复简单：** 若发现数据异常，从 audit trail 逆推业务状态；无需多源对账。

## Consequences

### Positive
- 审计与业务状态天然一致。
- 事故排查有完整的"决策链路"。

### Negative
- 事务边界变大（需同时写业务表 + 审计表）；可能增加锁竞争或长事务风险。改进方案：后续可优化为事务投影模式（事务内缓存 audit 事件，提交后异步持久化），但需保留重试机制。
- 审计表持续增长；需定期 archive 旧数据。

## Related Files
- `global_state_machine.md` §7（跨聚合事务承诺）
- `api_contracts/audit.md`（append-only event contract）
- `domain_models.md` §2.6（AuditTrailEvent 聚合）
