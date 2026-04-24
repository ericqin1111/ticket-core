# Program Roadmap

## Metadata

* **Status:** DRAFT / CLARIFIED
* **Owner:** qinric
* **Created At:** 2026-04-03
* **Doc Type:** Program roadmap / feature decomposition guide
* **Scope:** Headless Ticketing Fulfillment System Phase 1

---

## 1. 总体目标 (Program Objective)

本路线图用于收束当前 Phase 1 的 3 个 Epic，明确它们之间的依赖关系、推荐实施顺序、阶段性交付闸门，以及每个 Epic 的 feature 拆解入口。

目标不是新增需求，而是把已经确认完成的 Epic 规划整理成一条可执行的推进路径，避免后续在进入具体 feature 工作台时反复回到宏观边界层重新讨论。

Phase 1 的总体目标是：

* 先建立正确、安全、可恢复的交易履约内核。
* 再让已确认订单真正完成交付，并具备最小失败恢复能力。
* 最后把稳定内核以 Headless contract 形式对外暴露给渠道方。

---

## 2. 文档定位与组织映射 (Doc Placement)

按当前仓库文档组织，这份文件的定位是跨 Epic 的上位路线图文档。

它与当前文档体系的关系如下：

* `docs/ROADMAP.md`：负责描述跨 Epic 的推进顺序、边界、阶段目标与 feature 拆分建议。
* `docs/01_living_architecture/`：承载已经沉淀后的全局真相源，例如领域模型、全局状态机与 API contract。
* `docs/02_decision_records/`：承载具体实现过程中被确认的架构决策与取舍原因。
* `docs/03_features_workspace/feat-xxx/`：承载某个具体 feature 的工作台产物，例如 `1_pm_spec.md`、`2_blueprint.md`、`3_pr_summary.md`。

因此，这份路线图的职责是：

* 决定先做哪一个 Epic。
* 决定某个 Epic 应先拆出哪些 feature。
* 为后续 `feat-xxx` 工作台提供拆分入口，但不直接替代具体 feature 的 `1_pm_spec.md`。

---

## 3. 推荐实施顺序 (Recommended Execution Order)

推荐顺序固定为：

1. `EPIC-TKT-001: Core Transaction Backbone`
2. `EPIC-TKT-002: Fulfillment Execution & Recovery`
3. `EPIC-TKT-003: Channel & Headless Contract Foundation`

原因如下：

* `EPIC-TKT-001` 定义了交易主链路、库存安全、幂等、支付确认与 `Fulfillment.PENDING` 的起点，没有它，后续履约与渠道接入都没有稳定锚点。
* `EPIC-TKT-002` 建立真实交付闭环，让订单从“已确认”走到“已交付 / 失败待人工处理”，这一步必须建立在 Epic 1 已经确认的订单与履约起点上。
* `EPIC-TKT-003` 面向外部渠道暴露能力，必须建立在内部交易与履约状态机已经稳定收敛之后，否则对外 contract 会持续漂移。

---

## 4. 依赖关系 (Dependency Graph)

### EPIC-TKT-001

* 作为 Phase 1 基础 Epic，无前置依赖。

### EPIC-TKT-002

* 依赖 `EPIC-TKT-001`。
* 前置条件：系统已经能在 Payment Confirmation 后创建稳定的 `Fulfillment.PENDING`，并且订单状态机、幂等与 Audit Trail 基线已经成立。

### EPIC-TKT-003

* 依赖 `EPIC-TKT-001` 与 `EPIC-TKT-002`。
* 前置条件：系统已经具备稳定的交易状态与基础履约结果语义，能够把这些状态安全地映射为对外 Headless contract，而不是边开发边改 contract。

---

## 5. Epic 完成闸门 (Completion Gates)

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
* `PENDING -> PROCESSING -> SUCCEEDED / RETRY_PENDING / MANUAL_PENDING / FAILED` 的整体语义已稳定。
* 简单重试采用有限次退避模型，且不会无限重试。
* `RETRY_PENDING` 已稳定表达“允许系统继续自动恢复”的治理态。
* `MANUAL_PENDING` 已稳定表达“自动流程停止，等待人工接管”的治理态。
* `FAILED` 已收敛为“人工治理后确认终止”的最终失败终态。
* 人工介入入口与执行留痕成立。

### Gate for EPIC-TKT-003

达到以下条件，才认为 Phase 1 宏观目标完成：

* 外部渠道可以通过统一 `Channel Gateway` 访问基础 Headless contract。
* 基础渠道鉴权、查询接口与通知 contract 已稳定。
* 查询与状态推进采用已确认的混合 contract 风格并保持边界清晰。
* callback / webhook 具备基础版自动重试、投递状态与人工补发落点。

---

## 6. EPIC-TKT-002 目标澄清与阶段建议

本节用于明确 `EPIC-TKT-002` 当前讨论的目标边界。

重要说明：

* 本节只回答两个问题：`EPIC-TKT-002` 要做到什么，以及推荐分几阶段推进。
* 后续进入 FEATURE / PM 规格时，仍需要对每个阶段单独澄清业务规则。

### 6.1 EPIC-TKT-002 要做到什么

`EPIC-TKT-002` 的核心目标不是“把履约做复杂”，而是把 `Fulfillment.PENDING` 推进成一个可执行、可收敛、可恢复到人工处理的真实交付闭环。

在当前 Phase 1 语境下，它至少应覆盖：

* 系统能够自动扫描并拾取 `Fulfillment.PENDING`。
* 履约对象可以进入一次真实执行，而不是停留在投影或占位状态。
* 履约状态机至少形成 `PENDING -> PROCESSING -> SUCCEEDED / RETRY_PENDING / MANUAL_PENDING / FAILED` 的稳定治理语义。
* 系统能够识别“可自动重试失败”与“需终止并转人工处理失败”的基本差异。
* 自动重试必须有限次、可退避、不会无限循环。
* 当自动流程无法继续时，系统可以稳定收敛到 `MANUAL_PENDING`，而不是混入真正终局失败。
* 当履约经过人工治理仍确认失败时，系统可以最终收敛到 `FAILED`。
* 履约推进过程中的关键动作、关键失败和人工介入动作具备基础留痕。

换句话说，`EPIC-TKT-002` 的完成标准不是“接了多少供应商能力”，而是“订单确认后的履约生命周期已经可以被系统稳定托管”。

### 6.2 推荐分阶段推进

建议把 `EPIC-TKT-002` 拆成 3 个连续阶段，而不是一次性把所有恢复能力一起做完。

### Stage A: Fulfillment Processing Backbone

目标：

* 先把单次履约执行主链路建稳，让系统从 `Fulfillment.PENDING` 真正走到一次可观测的处理结果。

本阶段建议收敛为：

* 后台扫描 / 调度机制成立。
* 单次拾取、加锁、执行、落结果的主链路成立。
* `PENDING -> PROCESSING -> SUCCEEDED / MANUAL_PENDING` 的基本状态机成立。
* 先支持单一履约执行模型，不引入复杂路由与多提供方编排。
* 成功态和治理落点都能留下基础审计与诊断信息。

本阶段价值：

* 先回答“系统能不能真的执行履约”，并把失败先沉淀到清晰的治理入口，再回答“失败后如何恢复得更聪明”。

### Stage B: Retry & Failure Convergence

目标：

* 在执行主链路成立后，再补齐最小失败恢复能力，让系统具备有限自动恢复，而不是一次失败就完全依赖人工。

本阶段建议收敛为：

* 明确 transient failure 与 terminal failure 的业务分类方法。
* 为可重试失败引入 `RETRY_PENDING` 及有限次重试与退避模型。
* 为不可自动恢复失败引入稳定的 `MANUAL_PENDING` 收敛语义。
* 明确达到上限后的对象如何从自动恢复路径退出，而不是继续混用 `FAILED`。
* 为失败原因建立基础 reason code / error taxonomy，便于后续运营识别。
* 保证不会因为调度重复、执行超时或外部抖动而形成无限重试。

本阶段价值：

* 把履约系统从“能跑一次”推进到“面对常见抖动仍可自动收敛”，同时把自动恢复与人工接管边界拆清楚。

### Stage C: Manual Recovery Baseline

目标：

* 在自动履约与自动重试都已成立后，再补齐最小人工介入闭环，让 `MANUAL_PENDING` 真正成为可操作入口，并让 `FAILED` 只代表人工确认后的最终失败。

本阶段建议收敛为：

* 为人工介入定义明确入口，而不是依赖直接改库；该入口应对齐 `MANUAL_PENDING`。
* 支持最小必要的人工动作集合，例如人工重试、人工标记处理结果或人工终止。
* 所有人工作动作都必须具备操作留痕与原因记录。
* 人工动作与自动调度之间的状态边界需要固定，避免互相打架。

本阶段价值：

* 把“自动流程失败”转化为“可被运营接管的可治理流程”，并把人工确认失败与自动恢复失败严格区分开。

### 6.2.1 为什么要从“快速进入 FAILED”改为中间治理态

本次阶段回顾后，建议明确放弃“单次履约失败即立刻写成 `FAILED`”的早期简化设定，改为引入中间治理态。

原因如下：

* `FulfillmentAttempt` 表达的是“某一次执行是否成功”，而 `Fulfillment` 表达的是“整条履约目前处于什么治理阶段”；如果把单次 attempt 失败直接写成 `Fulfillment.FAILED`，会把两个层级的语义混在一起。
* Epic 2 既要支持自动重试，也要支持人工接管。如果 `FAILED` 同时承载“本次失败”“待人工处理”“最终确认失败”三种含义，后续 Stage B / Stage C 的边界会持续摇摆。
* 对外和对内都更清晰的方式，是把“仍可自动恢复”“已停止自动恢复、等待人工”“人工处理后确认失败”拆开表示。

因此，当前推荐的全局语义为：

* `RETRY_PENDING`：允许系统继续自动恢复的治理态。
* `MANUAL_PENDING`：自动流程已停止，等待人工接管的治理态。
* `FAILED`：经过人工治理后确认终止的最终失败态。

### 6.3 当前不建议在 EPIC-TKT-002 提前展开的内容

为避免 Epic 2 边界膨胀，以下内容建议明确不在当前阶段一并打包：

* 高级供应商路由与按策略择源。
* 部分履约、拆单履约、多阶段履约。
* 自动补偿编排与复杂 saga。
* 逆向履约、取消、退款。
* 面向外部渠道的复杂履约可视化 contract。

这些内容要么属于后续 Epic，要么应在 Epic 2 主干稳定后再讨论。

### 6.4 推荐讨论顺序

如果后续继续展开 `EPIC-TKT-002`，建议讨论顺序固定为：

1. 先确认 Stage A 的主干状态机与执行边界。
2. 再确认 Stage B 的失败分类、重试上限与终止语义。
3. 最后确认 Stage C 的人工介入动作、权限和留痕要求。

这样可以避免一开始就陷入人工操作细节或异常分支细节，导致主干迟迟定不下来。

---

## 7. Feature Workspace 拆解入口

每个 Epic 的第一个 feature 工作台建议固定如下：

### EPIC-TKT-001

* 首个 feature：`feat-TKT001-01: Reservation & Order Backbone`
* 原因：这是库存锁定、Order 建立和支付前状态的共同起点，必须先稳。

### EPIC-TKT-002

* 首个 feature：`feat-TKT002-01: Fulfillment Processing Backbone`
* 原因：先把单次履约执行主链路建稳，再讨论失败恢复与人工介入。

### EPIC-TKT-003

* 首个 feature：`feat-TKT003-01: Channel Identity & Gateway Baseline`
* 原因：先建立渠道入口与基础鉴权，再扩展对外查询和通知 contract。

如果继续顺着当前拆分方式推进，`EPIC-TKT-002` 建议优先拆成以下连续 feature：

1. `feat-TKT002-01: Fulfillment Processing Backbone`
2. `feat-TKT002-02: Retry & Failure Convergence`
3. `feat-TKT002-03: Manual Recovery Baseline`

建议这些 feature 统一落在：

* `docs/03_features_workspace/feat-TKT002-01/`
* `docs/03_features_workspace/feat-TKT002-02/`
* `docs/03_features_workspace/feat-TKT002-03/`

---

## 8. Phase 1 范围边界 (Phase Boundary)

以下能力明确不属于当前 Phase 1 总路线图：

* 复杂自动补偿编排。
* 高级供应商路由。
* 部分履约、多阶段履约。
* 复杂开放平台生态能力。
* 多租户深度隔离体系。
* 高阶运营治理与性能强化专项。

这些能力不应在 Phase 1 的 feature 工作台中被隐式带入。

---

## 9. 后续演进方向 (Future Evolution Directions)

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

## 10. 推荐推进方式 (Recommended Working Mode)

建议后续执行方式如下：

1. 先冻结当前 3 个 Epic 的宏观边界，不再继续在 Epic 层增加新能力。
2. 从 `EPIC-TKT-001` 的首个 feature 工作台开始向下分解。
3. 每完成一个 Epic 的完成闸门，再进入下一个 Epic，而不是前三个 Epic 并行下钻。
4. 在前三个 Epic 收敛后，再根据实际业务压力，在 `EPIC-TKT-004`、`EPIC-TKT-005`、`EPIC-TKT-006` 中选择后续演进方向。

这样做的原因是：

* 可以避免 contract、状态机与工作台拆分方式在多个方向同时漂移。
* 可以让每一层对上层假设保持稳定。
* 可以把风险集中暴露在最核心的交易履约链路上，而不是分散到外围能力。
