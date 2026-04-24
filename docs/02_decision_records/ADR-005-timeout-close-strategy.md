# ADR-005: Timeout Close Strategy — Polling + CAS Arbitration

**Status:** Accepted  
**Context:** RFC-TKT001-03  
**Date:** 2026-04-23

## Problem

Order 超时关闭需满足条件：
- `order.status = PENDING_PAYMENT`
- `order.payment_deadline_at <= now`

同时需与 Payment Confirmation 竞争赢家确定。可选策略：

1. **事件驱动（Event Sourcing）**：Order 创建时发送定时事件，TTL 到期触发。
   - 问题：时钟漂移、重复触发、事件丢失恢复复杂。
2. **分布式定时器（Quartz + DB）**：中心定时器写 trigger 表，扫描消费。
   - 问题：单体内已足够，引入复杂度不值。
3. **本地扫描 + CAS**（选中）：定时任务扫描候选，逐个 CAS 申报。

## Decision

采用**本地扫描 + CAS 仲裁**：

```sql
-- 扫描阶段（只读）
SELECT order_id, external_trade_no, reservation_id, payment_deadline_at
FROM ticket_order
WHERE status = 'PENDING_PAYMENT'
  AND payment_deadline_at <= NOW()
LIMIT 100;

-- 执行阶段（per candidate，写入）
BEGIN TRANSACTION;
  UPDATE ticket_order
  SET status = 'CLOSED', version = version + 1
  WHERE order_id = ? AND status = 'PENDING_PAYMENT' AND version = ?;
  
  IF rows_affected = 0:
    # CAS 失败，赢家已转移 order 状态（CONFIRMED或被他人关闭）
    RETURN "already_handled";
    ROLLBACK;
  ELSE:
    # CAS 成功，执行完整 T3 事务
    UPDATE reservation_record SET status = 'EXPIRED', version = version + 1 WHERE ...;
    UPDATE inventory_resource SET reserved_quantity = reserved_quantity + ?, version = version + 1 WHERE ...;
    INSERT INTO audit_trail_event (...) VALUES (...);
    COMMIT;
  END;
END;
```

## Rationale

1. **高效且原子：** CAS 仲裁由 DB 单行原子性保障，无竞争。
2. **幂等自然：** 若扫描发现同一 order 两轮均逾期，第二轮 CAS 必失败（status 已是 CLOSED）。
3. **扩展简单：** 后续分布式场景可迁移到中心定时器；本地扫描保持不变。
4. **可观测性：** scan_id + structured log 清晰记录扫描时间点与候选集。

## Consequences

### Positive
- 实现简洁，易测试易维护。
- 不依赖外部定时服务。

### Negative
- Phantom Read 风险：扫描 + 执行间隔内，新订单可能落入下一轮扫描无关的状态。改进由 feat-TKT001-04（分布式定时） 接管。
- 扫描延迟依赖批量上限与单轮耗时；需监控 P95 latency。

## Related Files
- `global_state_machine.md` §6（Payment vs Timeout 并发裁决）
- `api_contracts/order.md`（POST /orders 事务 T3）
- RFC-TKT001-03 §4.1.1（Timeout Close 边界定义）
