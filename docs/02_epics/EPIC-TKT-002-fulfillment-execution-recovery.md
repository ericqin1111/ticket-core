# EPIC-TKT-002: fulfillment-execution-recovery

## Metadata

* **Status:** DRAFT / CLARIFIED
* **Owner:** qinric
* **Created At:** 2026-04-03
* **Macro Architecture:** modular monolith
* **Primary Modules:** Fulfillment, Order, Operations & Governance
* **Depends On:** EPIC-TKT-001

---

## 1. 背景与业务目标 (Context & Business Objective)

本 Epic 聚焦已确认订单的实际交付执行问题。EPIC-TKT-001 已经解决“交易成立”和“支付确认后创建 `Fulfillment.PENDING`”的问题，但系统此时仍停留在“订单被确认”而非“票务已真正交付”的阶段。

本 Epic 的目标是让已确认订单真正进入履约执行链路，并在第一阶段具备基础失败恢复能力，使系统从“确认交易”进化到“完成交付”。

业务要解决的问题是：当 `Fulfillment.PENDING` 被创建后，系统如何以后台任务方式推进执行，如何把履约状态稳定推进到 `PROCESSING -> SUCCEEDED / FAILED`，如何在下游失败或不稳定时做简单重试，如何为人工介入预留明确入口，以及如何对履约结果和处理过程进行留痕。

该 Epic 的直接价值：

* 将“已确认订单”转化为“已完成交付订单”，闭合交易兑现链路。
* 在不引入复杂自动补偿编排的前提下，建立最小可恢复能力。
* 为后续高级供应商路由、复杂重试和人工运营治理提供清晰边界。

---

## 2. 核心约束 (NFRs & Invariants)

以下约束是本 Epic 的不可突破边界：

* Fulfillment 必须由后台推进，不在 Payment Confirmation 的同步链路内直接执行。
* Fulfillment 状态必须受严格约束，至少覆盖 `PENDING -> PROCESSING -> SUCCEEDED / FAILED`。
* 同一个 Fulfillment 不得因重复触发而并发执行多次。
* 简单重试必须受限，避免无限重试和状态震荡。
* 人工介入入口必须建立在明确失败状态和足够上下文之上。
* 履约执行过程与结果必须保留 Audit Trail。
* 第一阶段不引入复杂自动补偿编排，不做多阶段、部分履约等复杂模式。

---

## 3. In-Scope / Out-of-Scope

### ✅ In-Scope

* 后台任务扫描或拉起 `Fulfillment.PENDING` 并推进执行。
* Fulfillment 基础状态流转：`PENDING -> PROCESSING -> SUCCEEDED / FAILED`。
* 对下游履约执行失败提供简单重试机制。
* 提供人工介入入口，用于处理重试后仍失败的 Fulfillment。
* 对履约结果、失败原因、重试过程保留基础留痕。
* 明确 Fulfillment 与 Order 的关联约束，避免重复交付。

### ❌ Out-of-Scope

* 复杂自动补偿编排。
* 高级供应商路由、动态供应商选择。
* 部分履约、多阶段履约、拆单式履约。
* 全量运营后台能力。
* 高阶 SLA 编排、复杂优先级调度、弹性扩缩容专项。

---

## 4. Epic 切片建议 (RFC Roadmap Proposal)

为避免把执行状态机、失败恢复和运营介入混成一个超大 RFC，建议将本 Epic 拆为以下 3 个顺序 RFC：

### RFC-01: Fulfillment Processing Backbone

目标：

* 建立后台推进 `Fulfillment.PENDING` 的基本机制。
* 建立 `PENDING -> PROCESSING -> SUCCEEDED / FAILED` 的核心状态骨架。
* 明确一次履约任务的领取、执行、完成边界。

边界：

* 不实现复杂重试策略。
* 不实现人工介入工作台。
* 先把“如何真正执行一次履约”建稳。

### RFC-02: Retry and Failure Recovery

目标：

* 为失败的 Fulfillment 增加简单重试机制。
* 定义可重试失败与不可重试失败的第一阶段规则。
* 控制最大重试次数与失败落点。

边界：

* 不引入复杂补偿工作流。
* 不做智能退避与高级调度。
* 只处理“失败后如何有限次再试”。

### RFC-03: Manual Intervention and Audit Hardening

目标：

* 建立人工介入入口及基本操作语义。
* 补齐履约执行全过程 Audit Trail。
* 为运营人员提供最小必要上下文。

边界：

* 不做复杂运营控制台。
* 不做多角色审批链路。
* 只处理“如何看见失败并人工接管”。

---

## 5. 关键业务规则草案 (Business Rule Draft)

* 只有已确认订单关联的 `Fulfillment.PENDING` 才能进入后台执行。
* 同一个 Fulfillment 在任意时刻只能有一个有效执行上下文。
* Fulfillment 一旦进入 `SUCCEEDED`，不得再次执行。
* Fulfillment 进入 `FAILED` 后，表示自动履约流程已经终止，后续只能依赖人工介入处理。
* 重试次数必须有上限，超过上限后应进入 `FAILED` 并等待人工处理。
* 每一次执行尝试、失败原因、重试决策、人工操作都必须可追踪。

---

## 6. Decision Matrix

以下问题已完成业务侧拍板，后续 RFC 与设计阶段应严格以这些结论为前提：

| ID | Ambiguity / Decision Point | Option A | Option B | Final Decision |
| :- | :------------------------- | :------- | :------- | :------------- |
| 1 | 后台推进 Fulfillment 的触发基线 | 定时扫描 `PENDING` 任务。优势: 实现简单、与 Epic 1 的扫描模型一致；劣势: 时效略差。 | 事件驱动立即投递异步执行。优势: 响应更快；劣势: 失败恢复与重复投递控制更复杂。 | 选项 A |
| 2 | `FAILED` 的语义 | 达到失败条件后直接终态，人工介入需显式创建新尝试。优势: 状态简单；劣势: 人工修复链路更绕。 | `FAILED` 作为可恢复失败态，可被重试或人工介入重新推进。优势: 恢复语义直接；劣势: 状态约束更严格。 | 自定义结论：`FAILED` 表示自动履约流程在有限次重试后仍未成功，已进入需人工介入的终止态 |
| 3 | 简单重试策略 | 固定次数立即重试。优势: 实现最简单；劣势: 对短暂性下游故障不够友好。 | 固定次数 + 基础退避间隔。优势: 更贴近真实故障恢复；劣势: 调度与留痕复杂一点。 | 选项 B |
| 4 | 人工介入入口形式 | 先提供内部管理命令/API。优势: 足够支撑第一阶段；劣势: 运营可用性一般。 | 直接建设最小人工操作页面。优势: 更方便运营；劣势: 范围扩大到 UI。 | 选项 A |

---

## 7. 进入下阶段的准入条件 (Readiness Gate)

进入第一个 RFC 前，建议至少确认以下事项：

* 接受本 Epic 的 3 段式切片，不把执行主链路、重试恢复、人工介入一次性塞进单个 RFC。
* 后续设计必须遵守已确认的 4 项决策，尤其是定时扫描触发、`FAILED` 为需人工介入的终止态、有限次退避重试、内部命令/API 形式的人工入口。
* 明确第一阶段 Fulfillment Recovery 以“稳定、可追踪、可人工接管”为优先，而不是追求自动化最强。

---

## 8. 已确认设计前提 (Confirmed Design Premises)

* Fulfillment 执行采用定时扫描 `PENDING` 任务的后台推进模型，不采用事件驱动立即投递作为第一阶段基线。
* `FAILED` 的业务语义固定为：自动履约流程在有限次重试后仍未成功，已经停止自动推进，需人工介入。
* 简单重试采用“固定次数 + 基础退避间隔”策略，而不是立即重试打满次数。
* 人工介入入口第一阶段采用内部管理命令/API，不扩展到 UI 页面。
