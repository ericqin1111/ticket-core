# 3_pr_summary — feat-TKT001-01: Reservation & Order Backbone

**Feat ID:** `feat-TKT001-01`  
**Status:** ✅ COMPLETED  
**Implementation Date:** 2026-04-09

---

## 交付产物

- ✅ **Database:** 5 tables (catalog_item, inventory_resource, reservation_record, ticket_order, idempotency_record)
- ✅ **API endpoints:** 3 public routes (GET /catalog-items/.../sellable-availability, POST /reservations, POST /orders)
- ✅ **Service layer:** Reservation, Order, Catalog services with T_Res & T1 boundary implementations
- ✅ **Idempotency:** Action-scoped middleware (CREATE_RESERVATION, CREATE_ORDER)
- ✅ **Error handling:** 9 error codes (CATALOG_ITEM_NOT_SELLABLE, INSUFFICIENT_INVENTORY, RESERVATION_NOT_FOUND, RESERVATION_ALREADY_CONSUMED, RESERVATION_EXPIRED, IDEMPOTENCY_CONFLICT, ...)
- ✅ **Tests:** Unit, Integration, Concurrency tests (version CAS race simulation)

---

## 关键验证

| 场景 | 预期结果 | 状态 |
|:--|:--|:--|
| 正常流：查询 → 预留 → 创单 | 返回各自 ID，订单 PENDING_PAYMENT | ✅ |
| 库存不足并发 | 只有 qty 笔成功；余下 INSUFFICIENT_INVENTORY | ✅ |
| 预留幂等回放（同 key） | 返回缓存结果（同 res-id） | ✅ |
| 创单幂等回放（同 key） | 返回缓存结果（同 order-id） | ✅ |
| 创单前预留已消费 | RESERVATION_ALREADY_CONSUMED | ✅ |
| 创单前预留已过期 | RESERVATION_EXPIRED | ✅ |
| 并发创单同一预留 | 仅一笔成功；其余 RESERVATION_ALREADY_CONSUMED | ✅ |

---

## 已知局限

1. **无主动 TTL 扫描** — expires_at 存储但扫描释放待 feat-TKT001-03
2. **无审计追踪** — RESERVATION_CREATED/CONSUMED, ORDER_CREATED 事件待 feat-TKT001-03
3. **Fulfillment 未创建** — Order 暂停在 PENDING_PAYMENT，待 feat-TKT001-02

---

## 依赖与衔接

| feat | 依赖关系 | 用途 |
|:--|:--|:--|
| feat-TKT001-02 | **当前 feat → 02** | 需要 Order.PENDING_PAYMENT 现成状态 |
| feat-TKT001-03 | **当前 feat → 03** | 需要 Reservation / Order 基础上做超时 + audit |

---

## 审查检查清单

- [x] Migrations 适用于空库
- [x] Entity 和 schema 一致
- [x] 所有 public API 有 contract 文档（docs/01_living_architecture/api_contracts/）
- [x] Version CAS 逻辑无竞争漏洞
- [x] Idempotency key 隔离正确
- [x] 404/409/410 错误码合理
- [x] 集成测试覆盖主流程 + 边界情况
- [x] 性能基线（单创建 p95 < 100ms）

---

## 后续交付日期

无阻塞；feat-TKT001-02 可立即开始。
