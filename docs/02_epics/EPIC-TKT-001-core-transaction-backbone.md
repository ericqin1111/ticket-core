# EPIC-TKT-001: core-transaction-backbone

## Metadata

* **Status:** DRAFT / CLARIFIED
* **Owner:** qinric
* **Created At:** 2026-04-03
* **Macro Architecture:** modular monolith
* **Bounded Modules:** Catalog, Inventory, Order, Fulfillment, Channel Gateway, Operations & Governance

---

## 1. 背景与业务目标 (Context & Business Objective)

本 Epic 聚焦多渠道售票系统最核心的交易履约主链路，目标是在高并发场景下先建立一个可证明正确、可恢复、可审计的基础交易骨架，而不是一次性覆盖所有渠道差异、复杂履约和治理能力。

业务要解决的问题是：在 Headless Ticketing Fulfillment System 中，先确保 `Reservation -> Order -> Payment Confirmation -> Fulfillment.PENDING` 这条主链路可成立，并且在库存竞争、重复请求、支付回调重放、未支付超时、异常释放等关键场景下不破坏状态一致性。

该 Epic 的直接价值：

* 先验证“核心交易是否成立”，避免后续渠道接入建立在脆弱内核之上。
* 先固化库存安全、状态约束、幂等与 Audit Trail 这几条底线。
* 先把 Payment Confirmation 与 Fulfillment Execution 解耦，为第二阶段的履约执行与恢复打好边界。

---

## 2. 核心约束 (NFRs & Invariants)

以下约束是本 Epic 的不可突破边界：

* 核心库存控制必须避免 oversell、重复占用和异常释放。
* `Reservation -> Order -> Fulfillment` 必须采用严格状态约束，不允许跨状态跳转。
* 锁票、下单、支付确认等关键动作必须具备 Idempotency。
* Payment Confirmation 与 Fulfillment Execution 必须解耦。
* 未支付超时订单必须可靠释放 Reservation 与库存资源。
* 第一阶段 Fulfillment 只要求在支付确认后创建 `Fulfillment.PENDING`，不要求完成复杂执行。
* 关键状态变化必须保留 Audit Trail，便于追责、排障和人工介入。

---

## 3. In-Scope / Out-of-Scope

### ✅ In-Scope

* 最小可用 Catalog 配置能力，用于支撑可售商品与库存绑定。
* 可售库存查询，供锁票前做同步可售判断。
* Reservation 作为独立对象存在，支持创建、consume、超时释放。
* Order 创建流程，至少覆盖 `PENDING_PAYMENT -> CONFIRMED -> CLOSED`。
* 支付确认幂等处理，重复 Payment Confirmation 不得重复推进状态。
* 支付确认后只推进 `Order.CONFIRMED` 并创建 `Fulfillment.PENDING`。
* 对未支付超时订单进行可靠释放。
* 对 Reservation、Order、Payment Confirmation、Fulfillment 创建保留基础 Audit Trail。

### ❌ Out-of-Scope

* 复杂 Fulfillment Execution。
* 多渠道差异化接入细节与复杂渠道协议。
* 退款、取消、人工纠偏全流程。
* 高阶治理能力，包括复杂监控编排、容量治理、性能强化专项。
* 高级供应商路由、部分履约、多阶段履约。

---

## 4. Epic 切片建议 (RFC Roadmap Proposal)

为避免单个 RFC 过大、状态机与异常分支失控，建议将本 Epic 拆为以下 3 个顺序 RFC：

### RFC-01: Reservation and Order Backbone

目标：

* 建立最小 Catalog 配置与可售库存查询。
* 建立 Reservation 创建与 consume 的主流程。
* 建立 Order 创建及 `PENDING_PAYMENT` 初始状态。

边界：

* 不处理支付确认。
* 不处理超时释放的后台调度。
* 只要求明确 Reservation 与 Order 的状态关系。

### RFC-02: Payment Confirmation and Fulfillment Trigger

目标：

* 建立 Payment Confirmation 的 Idempotency 处理。
* 将 `Order.PENDING_PAYMENT` 推进到 `Order.CONFIRMED`。
* 创建 `Fulfillment.PENDING`，但不执行实际履约。

边界：

* 不实现复杂履约状态机。
* 不做回调渠道适配层扩展。
* 只定义“支付确认后如何推进内核状态”。

### RFC-03: Timeout Release and Audit Trail Hardening

目标：

* 建立未支付超时订单的可靠释放。
* 明确 Reservation 过期与 Order 关闭的协同规则。
* 补齐关键状态变更的 Audit Trail 要求。

边界：

* 不扩展为全链路运营治理中心。
* 不做高级补偿编排。
* 只处理关闭、释放、留痕这组稳定性能力。

---

## 5. 关键业务规则草案 (Business Rule Draft)

* Reservation 必须先于 Order 存在，Order 创建时 consume Reservation。
* 同一个 Reservation 只能被成功 consume 一次。
* 支付确认只能作用于处于 `PENDING_PAYMENT` 的有效 Order。
* 支付确认成功后，只能创建一个有效的 `Fulfillment.PENDING`。
* 未支付超时后，Order 必须进入 `CLOSED`，并释放其占用资源。
* 任一关键状态变化都必须产生可追踪的 Audit Trail 记录。

---

## 6. Decision Matrix

以下问题已完成业务侧拍板，后续 RFC 与设计阶段应严格以这些结论为前提：

| ID | Ambiguity / Decision Point | Option A | Option B | Final Decision |
| :- | :------------------------- | :------- | :------- | :------------- |
| 1 | Payment Confirmation 的接入语义 | 仅支持平台内同步确认接口。优势: 主链路简单、实现快；劣势: 真实渠道回调兼容性弱。 | 直接按异步 callback / webhook 语义设计。优势: 更贴近真实渠道；劣势: 第一阶段建模复杂度更高。 | 选项 B |
| 2 | Reservation 超时释放触发方式 | 依赖定时扫描关闭。优势: 实现简单、易审计；劣势: 释放存在秒级到分钟级延迟。 | 依赖延迟消息 / timer 触发。优势: 时效更好；劣势: 需要额外基础设施或更复杂的失败处理。 | 选项 A，且入口放在 Order 侧，由 Order 定时扫描联动 Reservation 释放 |
| 3 | 幂等键作用域 | 每个动作独立 Idempotency Key。优势: 语义清晰、便于分阶段重试；劣势: 渠道接入方需要管理多类 key。 | 统一以外部交易号作为跨动作幂等主键。优势: 渠道接入简单；劣势: 不同动作冲突与状态映射更复杂。 | 组合策略：用 `external_trade_no` 串联整笔交易，并用动作级 `Idempotency Key` 保护 Reservation、Order、Payment Confirmation |
| 4 | 可售库存控制基线 | 先以单库事务 + 行级并发控制建模。优势: 适配 modular monolith，落地最快；劣势: 极端吞吐上限更低。 | 提前引入缓存/预扣减模型。优势: 吞吐潜力更高；劣势: 第一阶段一致性与恢复复杂度显著上升。 | 选项 A，先做强一致性 |

---

## 7. 进入下阶段的准入条件 (Readiness Gate)

进入第一个 RFC 前，建议至少确认以下事项：

* 接受本 Epic 的 3 段式切片，不将主链路、支付确认、超时释放一次性塞进同一 RFC。
* 后续设计必须遵守已确认的 4 项决策，尤其是异步 Payment Confirmation、Order 侧定时扫描释放、双层幂等与强一致库存控制。
* 明确第一阶段以“正确性优先于吞吐极限”为指导原则。

---

## 8. 已确认设计前提 (Confirmed Design Premises)

* Payment Confirmation 按异步 callback / webhook 语义进入系统，但其结果仅推进 `Order.CONFIRMED` 并创建 `Fulfillment.PENDING`。
* 未支付超时释放采用定时扫描模型，由 Order 域作为扫描入口，并联动释放其关联 Reservation。
* 整笔交易的业务串联主键采用 `external_trade_no`，同时 Reservation、Order、Payment Confirmation 仍需各自动作级 Idempotency Key。
* 第一阶段库存控制采用单库事务内的强一致方案，不提前引入缓存预扣减。
