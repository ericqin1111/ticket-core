# ADR-007: Fulfillment 1:1 Invariant — Dual Protection (Rule + Constraint)

**Status:** Accepted  
**Context:** RFC-TKT001-02  
**Date:** 2026-04-23

## Problem

`Fulfillment` 作为 Payment Confirmation 成功后的投影，必须保证：
- 一个 Order 只能对应一个活跃 Fulfillment.PENDING（唯一性）。
- 创建失败不留悬挂状态（原子性）。

单纯依赖应用层 if-check 易漏：虽然代码检查了，但并发洞口或故障恢复时可导致脏数据。

## Decision

**双保险方案**：

1. **物理约束：** `UNIQUE KEY uk_fulfillment_record_order_id (order_id)`
   - 数据库层禁止重复；任何尝试第二次 INSERT 必 fail。

2. **业务规则：** Payment Confirmation Module 在 CAS 前检查
   ```java
   // CAS 前检查
   IF order.status != 'PENDING_PAYMENT':
     RETURN "ORDER_NOT_CONFIRMABLE";
   
   // CAS 推进
   UPDATE ticket_order SET status='CONFIRMED', version=version+1 WHERE ...;
   IF rows_affected == 0:
     // 输给他人，返回
     RETURN "ORDER_ALREADY_CONFIRMED_OR_CLOSED";
   
   // 检查 fulfillment 唯一性（应用层快速路径）
   IF fulfillment already exists for this order:
     RETURN "FULFILLMENT_ALREADY_EXISTS";
   
   // 创建 fulfillment
   INSERT INTO fulfillment_record (...) VALUES (...);
   ```

## Rationale

1. **故障恢复鲁棒：** 即使应用层 bug 导致多次尝试，数据库唯一约束止住脏数据。
2. **并发安全：** 物理约束与 version CAS 双重守护，无遗漏。
3. **清晰责任：** 业务规则负责快速路径（性能），物理约束负责终极防线（正确性）。

## Consequences

### Positive
- 无法创建重复 Fulfillment，数据完整性有保障。
- 并发场景下安全。

### Negative
- 应用层需显式处理 unique constraint violation，并区分"本次冲突"vs"他人已创建"。
- 后续若 Fulfillment 生命周期扩展（例如允许重新投影），需谨慎处理唯一约束。

## Related Files
- `domain_models.md` §2.5（Fulfillment 聚合定义）
- `api_contracts/payment.md`（POST /payments/confirmations 响应）
- `schema_summary.md`（fulfillment_record table uk 定义）
