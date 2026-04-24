# 1_pm_spec — feat-TKT001-01: Reservation & Order Backbone

**Feat ID:** `feat-TKT001-01`  
**Status:** ✅ COMPLETED  
**PM Date:** 2026-04-03

---

## 问题与机遇

当前系统无法稳定地锁定库存、创建预留、再原子消费为订单。这导致：
- 库存并发冲突未被明确约束
- 预留与订单之间存在悬空风险
- 支付前的交易起点不稳定

## 需求

建立"可售判断 → 锁票 → 创单"的最小交易骨架。需要：

1. **可售查询** — GET /catalog-items/{catalogItemId}/sellable-availability
   - 返回当前可售快照（总量 - 已锁定量）
   - 非幂等查询

2. **创建预留** — POST /reservations (with Idempotency-Key)
   - 锁定库存，创建 Reservation.ACTIVE
   - 预留有 TTL（参数 reservation_ttl_seconds）
   - 动作级幂等（CREATE_RESERVATION）

3. **创建订单** — POST /orders (with Idempotency-Key)
   - **原子**消费预留（ACTIVE → CONSUMED）并创建 Order.PENDING_PAYMENT
   - 无法绕过预留直接创单
   - 动作级幂等（CREATE_ORDER），**独立于** CREATE_RESERVATION

## 关键约束

| 约束 | 说明 |
|:--|:--|
| T1 原子性 | Reservation.ACTIVE→CONSUMED + Order.insert 同事务完成 |
| 幂等隔离 | CREATE_RESERVATION 与 CREATE_ORDER 各自 action-scoped，不共享 Idempotency-Key 空间 |
| 并发安全 | 库存并发扣减由 version CAS 仲裁 |

## 后续依赖

- `feat-TKT001-02` 需要 Order.PENDING_PAYMENT 状态
- `feat-TKT001-03` 需要 TTL 超时释放机制

## 完成标记

✅ 需求已在 Phase 1 完全实现。
