# 3_pr_summary — feat-TKT001-02: Payment Confirmation & Fulfillment Trigger

**Feat ID:** `feat-TKT001-02`  
**Status:** ✅ COMPLETED  
**Implementation Date:** 2026-04-12

---

## 交付产物

- ✅ **Database:** fulfillment_record table + extend ticket_order (confirmed_at column)
- ✅ **API endpoint:** POST /payments/confirmations (async callback entry)
- ✅ **Service layer:** PaymentConfirmationService with T2 boundary + CAS arbitration
- ✅ **Idempotency:** Extend action-scoped to PAYMENT_CONFIRMATION (separate from CREATE_*)
- ✅ **Concurrency:** Version CAS on ticket_order for Payment vs Timeout race
- ✅ **Error handling:** ORDER_NOT_CONFIRMABLE, FULFILLMENT_INVARIANT_BROKEN, + inherited codes
- ✅ **Tests:** Idempotency replay, uniqueness violation, CAS race, E2E integration

---

## 关键验证

| 场景 | 预期结果 | 状态 |
|:--|:--|:--|
| 正常流：支付回调 → 订单推进 → Fulfillment 创建 | 返回 {status=CONFIRMED, fulfillment_id, status=APPLIED} | ✅ |
| 同 key 幂等回放 | 返回相同 fulfillment_id，status=REPLAYED | ✅ |
| 订单不存在 | 404 ORDER_NOT_FOUND | ✅ |
| 订单已 CONFIRMED（他人快） | 409 ORDER_NOT_CONFIRMABLE | ✅ |
| 并发两个 Payment Confirmation 同订单 | 仅一笔 CAS 胜出；另一笔 ORDER_NOT_CONFIRMABLE | ✅ |
| Late payment（订单已 CLOSED by timeout） | 409 ORDER_NOT_CONFIRMABLE（待 feat-TKT001-03 实现 CLOSED） | ✅* |
| 同 Order 两次 Fulfillment 创建尝试 | UK 约束拒绝；app 返回 FULFILLMENT_INVARIANT_BROKEN | ✅ |

*feat-TKT001-03 实现后完整验证

---

## 已知局限

1. **无审计追踪** — PAYMENT_CONFIRMATION_APPLIED / _REJECTED 事件待 feat-TKT001-03
2. **Fulfillment 停留 PENDING** — 后续状态迁移（PROCESSING, DELIVERED）待后续 feat
3. **Channel Gateway 出表** — 本 feat 假定 callback 已通过鉴权 + 签名校验

---

## 依赖与衔接

| feat | 依赖关系 | 用途 |
|:--|:--|:--|
| feat-TKT001-01 | **01 → 当前 feat** | 需要 Order.PENDING_PAYMENT 现成状态 |
| feat-TKT001-03 | **当前 feat → 03** | fulfillment_id / Timeout Close 竞争基础 |

---

## 审查检查清单

- [x] Migration 可在 V1.0.0 后执行
- [x] Entity 和 schema 一致
- [x] API contract 与实现对齐（docs/01_living_architecture/api_contracts/payment.md）
- [x] Version CAS 逻辑无竞争窗口
- [x] Idempotency PAYMENT_CONFIRMATION 隔离正确
- [x] Fulfillment UK 约束生效
- [x] 409 vs 200 错误码语义明确
- [x] 集成测试覆盖幂等、竞争、异常路径
- [x] 性能基线（payment confirm p95 < 150ms）

---

## 后续交付日期

无阻塞；feat-TKT001-03 可立即开始（需 Timeout Sweep + Audit Trail）。
