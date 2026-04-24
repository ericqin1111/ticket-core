# 2_blueprint — feat-TKT001-01: Reservation & Order Backbone

**Feat ID:** `feat-TKT001-01`  
**Architect Date:** 2026-04-03

---

## 行为设计

### 1. 流程交互

```
Client
  ├─ GET /catalog-items/{catalogItemId}/sellable-availability
  │  └─ [sync in-process] Catalog 查询 Inventory → 返回可售快照
  │
  ├─ POST /reservations (Idempotency-Key: res-key-1)
  │  └─ [T_Res] Inventory.reserved_qty += qty (version CAS)
  │            Reservation.insert(status=ACTIVE, expires_at)
  │            idempotency_record.insert/update
  │
  └─ POST /orders (Idempotency-Key: ord-key-1)
     └─ [T1] Reservation.ACTIVE → CONSUMED (version CAS)
             Order.insert(status=PENDING_PAYMENT)
             idempotency_record.update
```

### 2. 状态机

```
Reservation:  ACTIVE ──(create order)──> CONSUMED ──(TTL)──> EXPIRED

TicketOrder:  PENDING_PAYMENT
              (awaiting payment confirmation in feat-TKT001-02)
```

### 3. 关键设计决策

| 决策 | 理由 |
|:--|:--|
| READ_COMMITTED + version CAS | 非阻塞高并发；无悲观行锁 |
| Action-scoped idempotency | 不共享 key 空间，避免冲突误触 |
| Reservation 不引入 RELEASED | 统一落为 EXPIRED，原因由 audit_code 区分 |

---

## 存储设计

### 表结构

**catalog_item**
- PK: catalog_item_id
- UK: (inventory_resource_id)
- 字段: name, status (DRAFT/ACTIVE/OFF_SHELF), version

**inventory_resource**
- PK: inventory_resource_id
- UK: (resource_code)
- 字段: total_quantity, reserved_quantity (version CAS target), status, version

**reservation_record**
- PK: reservation_id
- 字段: external_trade_no, catalog_item_id, inventory_resource_id, quantity, status (ACTIVE/CONSUMED/EXPIRED)
- 字段: expires_at, consumed_order_id (UK), consumed_at, version
- IDX: (status, expires_at) — TTL 扫描入口

**ticket_order**
- PK: order_id
- UK: (external_trade_no), (reservation_id)
- 字段: status (PENDING_PAYMENT/CONFIRMED/CLOSED), buyer_ref, payment_deadline_at, version

**idempotency_record**
- PK: idempotency_record_id
- UK: (action_name, idempotency_key)
- 字段: request_hash, external_trade_no, resource_type, resource_id, status (PROCESSING/SUCCEEDED/FAILED), response_payload

### 事务边界

**T_Res 单聚合事务**
```sql
BEGIN;
  UPDATE inventory_resource SET reserved_qty += ?, version = version+1 WHERE ... AND version = ?;
  [CAS fail → INSUFFICIENT_INVENTORY]
  INSERT INTO reservation_record (...);
  UPDATE idempotency_record SET status='SUCCEEDED', ...;
COMMIT;
```

**T1 跨聚合事务（Reservation Consume + Order Create）**
```sql
BEGIN;
  UPDATE reservation_record SET status='CONSUMED', version=version+1 
    WHERE reservation_id=? AND status='ACTIVE' AND version=?;
  [CAS fail → RESERVATION_ALREADY_CONSUMED]
  INSERT INTO ticket_order (...);
  UPDATE idempotency_record SET status='SUCCEEDED', ...;
COMMIT;
```

### 并发控制

- `inventory_resource.version` CAS：library 并发扣减
- `reservation_record.version` CAS：consume 唯一性  
- `uk_reservation_consumed_order_id`：物理约束防重
- `uk_ticket_order_reservation_id`：1:1 保证

---

## API 契约概览

| 方法 | 路径 | 幂等 | 返回 |
|:--|:--|:--|:--|
| GET | /catalog-items/{catalogItemId}/sellable-availability | ✓ | {sellable_quantity, reserved_quantity, status, checked_at} |
| POST | /reservations | ✓ | {reservation_id, status=ACTIVE, expires_at} |
| POST | /orders | ✓ | {order_id, status=PENDING_PAYMENT, payment_deadline_at} |

详见 `docs/01_living_architecture/api_contracts/`

---

## 实现指南

1. **表创建** — Flyway migration `V1.0.0__init_core_tables.sql`
2. **Entity** — CatalogItem, InventoryResource, Reservation, TicketOrder, IdempotencyRecord
3. **Service** — ReservationService(createReservation), OrderService(createOrder with T1)
4. **Controller** — @RestController with error mapping
5. **Idempotency middleware** — 拦截 Idempotency-Key header，查询/更新 idempotency_record
6. **Tests** — Unit(service), Integration(DB), Concurrent(version CAS race)
