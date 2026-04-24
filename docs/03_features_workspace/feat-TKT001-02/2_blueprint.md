# 2_blueprint — feat-TKT001-02: Payment Confirmation & Fulfillment Trigger

**Feat ID:** `feat-TKT001-02`  
**Architect Date:** 2026-04-07

---

## 行为设计

### 1. 流程交互

```
PaymentChannel (async)
  │
  ├─ callback/webhook with at-least-once delivery
  │
  └─ ChannelGateway
     ├─ authenticate / verify signature / normalize
     │
     └─ POST /payments/confirmations (Idempotency-Key: pay-key-1)
        │
        ├─ [Idempotency check]
        │  └─ action='PAYMENT_CONFIRMATION', key=pay-key-1 already SUCCEEDED?
        │     → YES: return cached {fulfillment_id=FUL-001, status=CONFIRMED}
        │
        └─ [T2 Payment Confirmation + Fulfillment Create]
           ├─ Load Order by external_trade_no
           ├─ Validate Order.status = PENDING_PAYMENT (business rule)
           ├─ CAS: Order.PENDING_PAYMENT → CONFIRMED (with version)
           │  └─ [CAS fail: other payment won OR timeout closed]
           │     → return ORDER_NOT_CONFIRMABLE
           ├─ INSERT Fulfillment.PENDING (with uk_fulfillment_record_order_id)
           │  └─ [UK violation: already exists]
           │     → application layer handles gracefully
           ├─ UPDATE idempotency_record SET status='SUCCEEDED',...
           └─ COMMIT → return {order_status=CONFIRMED, fulfillment_id=FUL-001, status=APPLIED}
```

### 2. 状态机

```
TicketOrder:  PENDING_PAYMENT ──(payment confirm CAS win)──> CONFIRMED
                               └─(timeout close, feat-03)──> CLOSED

Fulfillment:  [none] ──(payment confirm)──> PENDING ──(future feats)──> ...
```

### 3. 并发竞争（Payment vs Timeout）

```
Payment Confirmation          |  Timeout Sweep (feat-TKT001-03)
  ├─ CAS: PENDING_PAYMENT → CONFIRMED  |  CAS: PENDING_PAYMENT → CLOSED
  │  (version=N)              |  (version=N)
  └─ WINS: return success     |  WINS: return success
                              |
ORDER state = CONFIRMED       |  ORDER state = CLOSED
(互斥，后到达方读到非 PENDING_PAYMENT)
```

胜者完成 T2 / T3 事务；败者读到 `rows_affected=0`，返回 ORDER_NOT_CONFIRMABLE。

### 4. 关键设计决策

| 决策 | 理由 |
|:--|:--|
| Async callback（非同步确认） | 真实支付流程 at-least-once delivery，需重放 |
| Action-scoped PAYMENT_CONFIRMATION | 与其他 POST 接口 key 独立，避免误触 |
| Fulfillment 1:1（物理 UK + 业务规则） | 双重保险防重复创建 |
| 败者返回 ORDER_NOT_CONFIRMABLE | 不 reopen / 不重建 Fulfillment |

---

## 存储设计

### 新表 / 扩展

**fulfillment_record (NEW)**
- PK: fulfillment_id
- UK: (order_id) — 1:1 约束
- 字段: status (固定为 PENDING), payment_provider, provider_event_id, provider_payment_id, confirmed_at, channel_context_json, version

**ticket_order (EXTENDED)**
- 新字段: confirmed_at（PENDING_PAYMENT→CONFIRMED 时写入）
- 状态扩展: CONFIRMED（新增）

**idempotency_record (EXTENDED)**
- action_name 扩展: PAYMENT_CONFIRMATION（新增）
- resource_type 可值: FULFILLMENT（首次用于 fulfillment_record）

### 事务边界

**T2 Payment Confirmation + Fulfillment Create**
```sql
BEGIN;
  SELECT * FROM ticket_order WHERE external_trade_no = ? [read only];
  -- Validate: status = PENDING_PAYMENT
  
  UPDATE ticket_order
  SET status='CONFIRMED', confirmed_at=NOW(), version=version+1
  WHERE order_id=? AND status='PENDING_PAYMENT' AND version=?;
  [CAS fail → ORDER_NOT_CONFIRMABLE]
  
  INSERT INTO fulfillment_record 
    (fulfillment_id, order_id, status='PENDING', payment_provider, provider_event_id, confirmed_at, ...)
  VALUES (...);
  [UK violation: FULFILLMENT_INVARIANT_BROKEN if surprise duplicate]
  
  UPDATE idempotency_record 
  SET status='SUCCEEDED', resource_type='FULFILLMENT', resource_id=<fulfillment_id>, response_payload=...;
  
COMMIT;
```

### 并发控制

- `ticket_order.version` CAS：胜者确定（Payment vs Timeout）
- `fulfillment_record.uk_fulfillment_record_order_id`：防重复创建
- `idempotency_record(action_name, key)` UK：防重复处理

---

## API 契约概览

| 方法 | 路径 | 幂等 | 返回 205 / 409 |
|:--|:--|:--|:--|
| POST | /payments/confirmations | ✓ | {order_status=CONFIRMED, fulfillment_id, status=APPLIED\|REPLAYED} |

详见 `docs/01_living_architecture/api_contracts/payment.md`

---

## 实现指南

1. **表扩展** — Flyway `V1.0.1__add_fulfillment_and_extend_order.sql`
2. **Entity** — 新增 Fulfillment；扩展 TicketOrder 字段
3. **Service** — PaymentConfirmationService(confirmPayment with T2 + CAS)
4. **Controller** — POST /payments/confirmations with error mapping
5. **Idempotency** — 扩展中间件支持 PAYMENT_CONFIRMATION
6. **Tests** — Unit(payment logic), Idempotency(replay), Concurrency(CAS race), Integration(E2E)
