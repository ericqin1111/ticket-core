# ADR-001: Non-Blocking Concurrency via READ_COMMITTED + Optimistic Locking

**Status:** Accepted  
**Context:** RFC-TKT001-01 / RFC-TKT001-02 / RFC-TKT001-03  
**Date:** 2026-04-23

## Problem

多个操作可能在秒级时间窗口内并发竞争同一资源（例如 Payment Confirmation vs Timeout Sweep 竞争 `TicketOrder.PENDING_PAYMENT`）。

传统悲观策略（`SELECT FOR UPDATE`）会导致：
- 长事务加锁持有，阻塞其他读/写。
- 高并发下死锁风险。
- 扫描类操作（Timeout Sweep）大批量锁表压力。

## Decision

所有事务统一采用：
- **隔离级别：** `READ_COMMITTED`（MySQL default for InnoDB）
- **并发控制：** 乐观锁（每行业务对象持有 `version` 字段）
- **争议仲裁：** `UPDATE ... WHERE ... AND version=? THEN version:=version+1`  
  首个完成 CAS 的赢家；其他并发操作读到 `rows_affected=0`，判定失败并返回稳定业务错误。

## Rationale

1. **非阻塞性：** `READ_COMMITTED` 下只锁对象本身，读操作不加锁，允许高并发。
2. **扫描友好：** Timeout Sweep 大批量扫描候选，只在执行阶段 CAS 争议，避免锁爆炸。
3. **容错性：** 失败方安全地退出（返回 `ORDER_NOT_CONFIRMABLE` / 下一轮扫描重试），无需回滚补偿。
4. **一致性下界：** `READ_COMMITTED` 不保证 RR，但结合 version CAS 能保证单行原子性；跨行不变量由应用层 CAS + 物理约束（UK / FK）保障。

## Consequences

### Positive
- 高并发场景性能稳定，无死锁。
- Timeout Sweep 扫描不卡主业务。

### Negative
- 需显式管理每个业务网聚合的 `version` 字段。
- Phantom Read 风险：扫描类操作（Timeout Sweep）可能遗漏边界行。改进方案由 feat-TKT001-04 接管。

## Related Files
- `domain_models.md` §2.1 ~ 2.6（各聚合 version 字段）
- `global_state_machine.md` §6（并发裁决规则）
- `api_contracts/order.md`（POST /orders 事务边界）
