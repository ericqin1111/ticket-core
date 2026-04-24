# 1_pm_spec — feat-TKT001-03: Timeout Release & Audit Trail Hardening

**Feat ID:** `feat-TKT001-03`  
**Status:** ✅ COMPLETED (IMPL)  
**PM Date:** 2026-04-08

---

## 问题与机遇

虽然订单链路完整（feat-01/02），但缺少两个关键底座：
1. **未支付超时** — 如不及时关闭，会长期占用库存、Reservation，侵蚀可售能力
2. **事件溯源** — 没有审计追踪，无法证明系统执行正确、无法分析故障

## 需求

补齐"超时关闭、库存回补、事件留痕"的完整闭环。需要：

1. **Timeout Sweep（内部定时扫描）**
   - 定期扫描 `Order.PENDING_PAYMENT && payment_deadline_at <= now`
   - 逐个 CAS 关闭；胜者触发完整 cleanup，败者跳过
   - 不暴露 public API（内部调度）

2. **Reservation 联动释放**
   - `Reservation.CONSUMED → EXPIRED`（reason_code = ORDER_PAYMENT_TIMEOUT）
   - 复用现有 EXPIRED 状态，不新增 RELEASED

3. **库存回补**
   - Timeout close 时恢复 `reserved_quantity`
   - 与订单关闭、Reservation 释放同事务

4. **基础 Audit Trail**
   - 记录 11 个关键事件类型（RESERVATION_CREATED, CONSUMED, RELEASED, ORDER_CREATED, CONFIRMED, TIMEOUT_CLOSED, PAYMENT_CONFIRMATION_APPLIED/REPLAYED/REJECTED, FULFILLMENT_CREATED, INVENTORY_RESTORED）
   - Append-only，不支持修改删除

## 关键约束

| 约束 | 说明 |
|:--|:--|
| T3 原子性 | Order.CLOSED + Reservation.EXPIRED + Inventory restore + Audit append 同事务 |
| CAS 仲裁 | Payment Confirmation vs Timeout Sweep 互斥（order.version CAS） |
| 一致性基线 | 强一致性（单库本地事务），非最终一致 |
| 晚到支付拒绝 | 订单已 CLOSED 后,支付确认返回 ORDER_NOT_CONFIRMABLE，不重建 Fulfillment |

## 后续依赖

- 不依赖其他 feat；是 Phase 1 的最后一环
- Phase 2 feat （履约执行、退款等）基于本 feat 的事件记录

## 完成标记

✅ 需求已在 Phase 1 完全实现。
