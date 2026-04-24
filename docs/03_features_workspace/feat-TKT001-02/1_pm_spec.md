# 1_pm_spec — feat-TKT001-02: Payment Confirmation & Fulfillment Trigger

**Feat ID:** `feat-TKT001-02`  
**Status:** ✅ COMPLETED  
**PM Date:** 2026-04-07

---

## 问题与机遇

有了订单落点（feat-TKT001-01），但暂停在 PENDING_PAYMENT。现在需要：
- 接收支付渠道的异步回调
- 推进订单到 CONFIRMED
- 创建唯一的 Fulfillment.PENDING 作为后续履约入口

## 需求

建立"支付异步确认 → 订单确认 → 履约投影"的闭环。需要：

1. **支付确认入口** — POST /payments/confirmations (with Idempotency-Key)
   - 接收支付渠道 callback（webhook / async RPC）
   - 校验同一 external_trade_no + key 幂等性
   - 动作级幂等（PAYMENT_CONFIRMATION），**独立于** CREATE_RESERVATION / CREATE_ORDER

2. **订单状态推进** — Order.PENDING_PAYMENT → Order.CONFIRMED
   - 仅允许在 PENDING_PAYMENT 时推进
   - 并发安全（与后续 feat-TKT001-03 Timeout Sweep 竞争）

3. **履约投影创建** — Fulfillment.PENDING
   - **唯一性：** 1 Order 对应最多 1 Fulfillment
   - 作为后续履约执行的稳定起点
   - 本 feat 不展开履约执行（状态迁移在后续 feat）

## 关键约束

| 约束 | 说明 |
|:--|:--|
| T2 原子性 | Order.PENDING_PAYMENT→CONFIRMED + Fulfillment.PENDING.insert 同事务 |
| 版本竞争 | Order.version CAS 保证 Payment Confirmation vs Timeout Sweep 互斥 |
| 幂等隔离 | PAYMENT_CONFIRMATION action-scoped；同 key 两次到达返回相同 fulfillment_id |
| 唯一约束 | fulfillment_record.uk(order_id) 物理保证 1:1 |

## 后续依赖

- `feat-TKT001-03` 需要 Order.CONFIRMED 状态以实现超时竞争
- `feat-TKT001-03` 需要 fulfillment_record 表以记录 FULFILLMENT_CREATED 审计事件

## 完成标记

✅ 需求已在 Phase 1 完全实现。
