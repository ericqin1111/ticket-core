# 2_blueprint — feat-TKT001-03: Timeout Release & Audit Trail Hardening

**Feat ID:** `feat-TKT001-03`  
**Architect Date:** 2026-04-08

---

## 行为设计

### 1. Timeout Sweep Scheduler

```
Every 30-60s (internal trigger, no public API):
  ├─ SELECT order_id, external_trade_no, reservation_id, payment_deadline_at
  │  FROM ticket_order
  │  WHERE status='PENDING_PAYMENT' AND payment_deadline_at <= now
  │  LIMIT 500  [bounded batch]
  │
  └─ For each candidate:
     ├─ [T3] BEGIN TRANSACTION
     ├─ SELECT * FROM ticket_order WHERE order_id=? FOR update-check
     ├─ IF status != PENDING_PAYMENT OR payment_deadline_at > now:
     │  └─ SKIP (order state changed or no longer overdue)
     │
     ├─ UPDATE ticket_order SET status='CLOSED', version=version+1 WHERE ... AND version=?
     ├─ [CAS fail → other payment/sweep won, skip]
     │
     ├─ UPDATE reservation SET status='EXPIRED', version=version+1 WHERE ... AND version=?
     ├─ UPDATE inventory SET reserved_qty -= qty, version=version+1 WHERE ... AND version=?
     ├─ INSERT audit_trail_event (ORDER_TIMEOUT_CLOSED, RESERVATION_RELEASED, INVENTORY_RESTORED)
     ├─ COMMIT [all-or-nothing]
     │
     └─ [Structured logging] scan_id, request_id, decision, reason_code, latency
```

### 2. Payment Confirmation After Timeout

```
POST /payments/confirmations → order already status='CLOSED'

├─ Idempotency check (as before)
└─ [T2-reject] BEGIN TRANSACTION
   ├─ SELECT * FROM ticket_order WHERE external_trade_no=?
   ├─ IF status = 'CLOSED':
   │  └─ [Cannot reopen] Insert audit PAYMENT_CONFIRMATION_REJECTED (reason=CLOSED_BY_TIMEOUT)
   │     ROLLBACK
   │     Return 409 ORDER_NOT_CONFIRMABLE
   │
   └─ IF status = 'PENDING_PAYMENT':
      └─ [Normal payment flow, as feat-02]
```

### 3. 状态机（更新）

```
Reservation:  ACTIVE ──(create order)──> CONSUMED ──(TTL)──> EXPIRED
                                             └─(timeout)─────┘

TicketOrder:  PENDING_PAYMENT ──(payment win)──────> CONFIRMED
                               └─(timeout win)──> CLOSED

Payment vs Timeout race on PENDING_PAYMENT:
  - Both CAS on same order.version
  - First to succeed wins; loser reads rows_affected=0
  - Loser returns stable error (ORDER_NOT_CONFIRMABLE)
```

### 4. 关键设计决策

| 决策 | 理由 |
|:--|:--|
| Polling + CAS（非 MQ/Outbox） | 单体内简单可扩展；无外部依赖 |
| Reservation 无新状态，用 reason_code 区分 | 状态简洁；原因灵活扩展 |
| 强一致 T3 事务 | Phase 1 容量小；不引入最终一致复杂度 |
| Append-only Audit | 不可逆，法律追溯友好 |

---

## 存储设计

### 新表

**audit_trail_event**
- PK: event_id
- 字段: event_type (ENUM), external_trade_no, occurred_at, source_module
- 字段: order_id, reservation_id, fulfillment_id, inventory_resource_id, provider_event_id (optional)
- 字段: reason_code (optional), payload_summary (JSON), created_at

**Indexes:** (external_trade_no), (event_type), (occurred_at)

### 扩展列

**ticket_order**
- 新状态：status 扩展支持 CLOSED

**reservation_record**
- reason_code 字段（optional，存在于 EXPIRED 状态时）

### 修改 idempotency_record

- action_name 扩展支持 TIMEOUT_CLOSE（internal action，不需要客户端 Idempotency-Key）

### 事务边界

**T3 Timeout Close (Order Close + Reservation Release + Inventory Restore + Audit)**
```sql
BEGIN;
  -- Re-check order state (防止 phantom read 与并发干扰)
  SELECT * FROM ticket_order WHERE order_id=? [read only];
  -- Assert: status = PENDING_PAYMENT AND payment_deadline_at <= now
  
  -- CAS: close order
  UPDATE ticket_order
  SET status='CLOSED', version=version+1
  WHERE order_id=? AND status='PENDING_PAYMENT' AND version=?;
  [CAS fail → skip this candidate (already handled)]
  
  -- CAS: release reservation
  UPDATE reservation_record
  SET status='EXPIRED', reason_code='ORDER_PAYMENT_TIMEOUT', version=version+1
  WHERE reservation_id=? AND status='CONSUMED' AND version=?;
  
  -- CAS: restore inventory
  UPDATE inventory_resource
  SET reserved_quantity = reserved_quantity - ?, version=version+1
  WHERE inventory_resource_id=? AND version=?;
  
  -- Append events (must all succeed or all fail)
  INSERT INTO audit_trail_event (event_type='ORDER_TIMEOUT_CLOSED', ...) VALUES (...);
  INSERT INTO audit_trail_event (event_type='RESERVATION_RELEASED', ...) VALUES (...);
  INSERT INTO audit_trail_event (event_type='INVENTORY_RESTORED', ...) VALUES (...);
  
COMMIT;
```

**T2-reject Payment After Timeout (Optional, separate txn)**
```sql
BEGIN;
  INSERT INTO audit_trail_event (event_type='PAYMENT_CONFIRMATION_REJECTED', ...) VALUES (...);
COMMIT;
```

---

## API 概览

| 触发方 | 内容 | 返回 | 幂等 |
|:--|:--|:--|:--|
| Scheduler（内部） | Timeout Sweep | 关闭订单数 | ✓（幂等重试） |
| Payment Channel（晚到） | POST /payments/confirmations 到 CLOSED 订单 | 409 ORDER_NOT_CONFIRMABLE | ✓（幂等缓存） |

---

## 实现指南

1. **表创建** — Flyway `V1.0.2__add_audit_trail_and_extend_state.sql`
2. **Entity** — AuditTrailEvent
3. **Service** — OrderTimeoutSweepService(scanAndClose), PaymentConfirmationService(handle late rejects)
4. **Scheduler** — Spring @Scheduled(fixedDelay=30s～60s) 触发 sweep
5. **Audit module** — AuditTrailService(append) with strong txn guarantee
6. **Tests** — Timeout sweep race, late payment rejection, Audit event coverage
7. **Monitoring** — Metrics (scan_count, close_count, late_payment_reject_count), structured logs
