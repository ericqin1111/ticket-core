# EPIC-TKT-000: program-roadmap

## Metadata

* **Status:** DRAFT / CLARIFIED
* **Owner:** qinric
* **Created At:** 2026-04-03
* **Scope:** Headless Ticketing Fulfillment System phase-1 roadmap

---

## 1. 总体目标 (Program Objective)

本路线图用于收束当前 Phase 1 的 3 个 Epic，明确它们之间的依赖关系、推荐实施顺序、阶段性交付闸门，以及每个 Epic 的 RFC 拆解入口。

目标不是新增需求，而是把已经确认完成的 Epic 规划整理成一条可执行的推进路径，避免后续在进入 RFC 时反复回到宏观边界层重新讨论。

Phase 1 的总体目标是：

* 先建立正确、安全、可恢复的交易履约内核。
* 再让已确认订单真正完成交付，并具备最小失败恢复能力。
* 最后把稳定内核以 Headless contract 形式对外暴露给渠道方。

---

## 2. 推荐实施顺序 (Recommended Execution Order)

推荐顺序固定为：

1. `EPIC-TKT-001: Core Transaction Backbone`
2. `EPIC-TKT-002: Fulfillment Execution & Recovery`
3. `EPIC-TKT-003: Channel & Headless Contract Foundation`

原因如下：

* `EPIC-TKT-001` 定义了交易主链路、库存安全、幂等、支付确认与 `Fulfillment.PENDING` 的起点，没有它，后续履约与渠道接入都没有稳定锚点。
* `EPIC-TKT-002` 建立真实交付闭环，让订单从“已确认”走到“已交付 / 失败待人工处理”，这一步必须建立在 Epic 1 已经确认的订单与履约起点上。
* `EPIC-TKT-003` 面向外部渠道暴露能力，必须建立在内部交易与履约状态机已经稳定收敛之后，否则对外 contract 会持续漂移。

---

## 3. 依赖关系 (Dependency Graph)

### EPIC-TKT-001

* 作为 Phase 1 基础 Epic，无前置依赖。

### EPIC-TKT-002

* 依赖 `EPIC-TKT-001`。
* 前置条件：系统已经能在 Payment Confirmation 后创建稳定的 `Fulfillment.PENDING`，并且订单状态机、幂等与 Audit Trail 基线已经成立。

### EPIC-TKT-003

* 依赖 `EPIC-TKT-001` 与 `EPIC-TKT-002`。
* 前置条件：系统已经具备稳定的交易状态与基础履约结果语义，能够把这些状态安全地映射为对外 Headless contract，而不是边开发边改 contract。

---

## 4. Epic 完成闸门 (Completion Gates)

### Gate for EPIC-TKT-001

达到以下条件，才建议进入 `EPIC-TKT-002`：

* `Reservation -> Order -> Payment Confirmation -> Fulfillment.PENDING` 主链路闭环成立。
* 库存控制在第一阶段强一致模型下不发生 oversell、重复占用和异常释放。
* `Reservation`、`Order`、`Payment Confirmation` 关键动作具备既定幂等保障。
* Order 侧定时扫描超时关闭与 Reservation 联动释放规则成立。
* 关键状态变化具备基础 Audit Trail。

### Gate for EPIC-TKT-002

达到以下条件，才建议进入 `EPIC-TKT-003`：

* `Fulfillment` 可以由后台扫描推进。
* `PENDING -> PROCESSING -> SUCCEEDED / FAILED` 状态流转成立。
* 简单重试采用有限次退避模型，且不会无限重试。
* `FAILED` 已稳定为“自动履约终止、需人工介入”的终止态。
* 人工介入入口与执行留痕成立。

### Gate for EPIC-TKT-003

达到以下条件，才认为 Phase 1 宏观目标完成：

* 外部渠道可以通过统一 `Channel Gateway` 访问基础 Headless contract。
* 基础渠道鉴权、查询接口与通知 contract 已稳定。
* 查询与状态推进采用已确认的混合 contract 风格并保持边界清晰。
* callback / webhook 具备基础版自动重试、投递状态与人工补发落点。

---

## 5. RFC 拆解入口 (RFC Entry Points)

每个 Epic 的第一个 RFC 建议固定如下：

### EPIC-TKT-001

* 首个 RFC：`Reservation and Order Backbone`
* 原因：这是库存锁定、Order 建立和支付前状态的共同起点，必须先稳。

### EPIC-TKT-002

* 首个 RFC：`Fulfillment Processing Backbone`
* 原因：先把单次履约执行主链路建稳，再讨论失败恢复与人工介入。

### EPIC-TKT-003

* 首个 RFC：`Channel Identity and Gateway Baseline`
* 原因：先建立渠道入口与基础鉴权，再扩展对外查询和通知 contract。

---

## 6. Phase 1 范围边界 (Phase Boundary)

以下能力明确不属于当前 Phase 1 总路线图：

* 复杂自动补偿编排。
* 高级供应商路由。
* 部分履约、多阶段履约。
* 复杂开放平台生态能力。
* 多租户深度隔离体系。
* 高阶运营治理与性能强化专项。

这些能力不应在 Phase 1 的 RFC 中被隐式带入。

---

## 7. 后续演进方向 (Future Evolution Directions)

以下 Epic 不属于当前 Phase 1 的已承诺执行范围，但代表系统在完成前 3 个 Epic 之后，可以继续前进的主要方向。

重要说明：

* 这些方向当前权重相同，不代表优先级排序。
* 它们是“可前进的方向”，不是当前阶段必须立刻进入的 backlog。
* 后续是否展开，应取决于业务压力、运营反馈、渠道需求和生产事故画像，而不是预设先后顺序。

### EPIC-TKT-004: Operations, Audit & Manual Governance

目标：

* 补齐 production system 的治理底座，让系统不仅能跑，还能被运营、被排障、被追责。

范围建议：

* Audit Trail 强化
* 操作留痕
* 人工纠偏
* 异常单查询
* 基础对账基线

本质定位：

* 把“系统可运行”推进到“系统可治理、可排障、可追责”。

### EPIC-TKT-005: Reverse Flow: Cancellation & Refund

目标：

* 把逆向履约链路正式纳入系统，而不是只做正向 Happy Path。

范围建议：

* 订单取消
* 退款申请与处理
* 逆向履约状态收敛
* 必要的资源回补或权益撤销
* 逆向流程审计

本质定位：

* 补齐真实交易系统最关键的另一半，使系统从“只会卖”进化到“能完整处理交易生命周期”。

### EPIC-TKT-006: Reliability & Scalability Hardening

目标：

* 在主链路跑通后，开始做更偏工程强化的能力。

范围建议：

* 限流与隔离
* 重试策略强化
* 性能压测基线
* 热点识别
* 为未来物理拆分预留演进点

本质定位：

* 把“功能成立”推进到“高压场景下依然稳定、可扩展、可演进”。

---

## 8. 推荐推进方式 (Recommended Working Mode)

建议后续执行方式如下：

1. 先冻结当前 3 个 Epic 的宏观边界，不再继续在 Epic 层增加新能力。
2. 从 `EPIC-TKT-001` 的首个 RFC 开始向下分解。
3. 每完成一个 Epic 的完成闸门，再进入下一个 Epic，而不是前三个 Epic 并行下钻。
4. 在前三个 Epic 收敛后，再根据实际业务压力，在 `EPIC-TKT-004`、`EPIC-TKT-005`、`EPIC-TKT-006` 中选择后续演进方向。

这样做的原因是：

* 可以避免 contract 与状态机在多个方向同时漂移。
* 可以让每一层对上层假设保持稳定。
* 可以把风险集中暴露在最核心的交易履约链路上，而不是分散到外围能力。
