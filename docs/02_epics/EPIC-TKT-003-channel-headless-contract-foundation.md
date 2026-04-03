# EPIC-TKT-003: channel-headless-contract-foundation

## Metadata

* **Status:** DRAFT / CLARIFIED
* **Owner:** qinric
* **Created At:** 2026-04-03
* **Macro Architecture:** modular monolith
* **Primary Modules:** Channel Gateway, Catalog, Inventory, Order, Fulfillment
* **Depends On:** EPIC-TKT-001, EPIC-TKT-002

---

## 1. 背景与业务目标 (Context & Business Objective)

前两个 Epic 已分别解决核心交易骨架与基础履约恢复能力，但系统仍主要停留在内核视角，尚未把这些能力以稳定、可消费、可隔离的 Headless contract 对外暴露。

本 Epic 的目标是建立面向外部渠道的 Headless contract foundation，让渠道方能够通过统一 API 和基础 callback / webhook contract 访问活动、库存、订单和履约相关能力，同时具备最小必要的身份识别与鉴权机制。

业务要解决的问题是：如何在不引入复杂开放平台和多租户治理体系的前提下，先把交易履约内核以稳定一致的方式暴露给外部渠道消费，使后续渠道接入不必直接耦合内部模块实现细节。

该 Epic 的直接价值：

* 将内部交易履约内核产品化为可复用的 Headless 能力层。
* 降低未来新增渠道时的集成成本和契约漂移风险。
* 为后续更复杂的开放平台能力、多渠道治理和回调生态奠定统一接口基础。

---

## 2. 核心约束 (NFRs & Invariants)

以下约束是本 Epic 的不可突破边界：

* 外部渠道必须通过统一 Channel Gateway 访问核心能力，不直接耦合内部模块。
* 渠道身份识别与基础鉴权必须先于业务访问生效。
* 对外 contract 必须保持资源语义稳定，避免直接暴露内部聚合和临时状态；查询与状态推进可以采用不同风格，但边界必须清晰。
* 活动、库存、订单基础查询与关键 callback / webhook contract 必须具备一致命名与错误语义。
* 第一阶段只做 Headless foundation，不扩展为复杂开放平台或多租户深度隔离系统。
* 不因渠道适配需求反向破坏前两个 Epic 已确认的核心交易与履约约束。

---

## 3. In-Scope / Out-of-Scope

### ✅ In-Scope

* Channel Gateway 的基础入口与路由边界。
* 渠道身份识别与基础鉴权。
* 统一 API contract。
* 活动 / 库存 / 订单基础查询能力。
* 基础 Webhook / callback contract。
* 对渠道请求与回调结果保留基础留痕。

### ❌ Out-of-Scope

* 复杂渠道运营后台。
* 多协议并行适配框架。
* 多租户深度隔离体系。
* 复杂开放平台生态能力，例如开发者门户、应用市场、细粒度计费。
* 面向渠道的高级限流、配额编排、版本治理平台。

---

## 4. Epic 切片建议 (RFC Roadmap Proposal)

为避免把鉴权、查询 contract、回调 contract 混成一个接口大杂烩，建议将本 Epic 拆为以下 3 个顺序 RFC：

### RFC-01: Channel Identity and Gateway Baseline

目标：

* 建立 Channel Gateway 的基础接入边界。
* 建立渠道身份识别与基础鉴权能力。
* 统一渠道请求的入口约束和错误返回基线。

边界：

* 不扩展复杂权限模型。
* 不做开放平台式应用管理。
* 先把“谁能进来、怎么安全进来”建稳。

### RFC-02: Headless Read Contracts

目标：

* 暴露活动、库存、订单的基础查询接口。
* 统一外部 contract 的资源命名、分页/过滤基线和错误语义。
* 确保对外查询 contract 与内部域模型解耦。

边界：

* 不直接暴露内部全量对象。
* 不做复杂搜索、报表、聚合分析接口。
* 只处理渠道接入最基础的读能力。

### RFC-03: Callback and Webhook Foundation

目标：

* 建立基础 callback / webhook contract。
* 明确对外通知的事件范围、幂等要求和签名/校验边界。
* 为渠道侧对账和状态跟踪提供最小必要通知能力。

边界：

* 不做复杂事件订阅中心。
* 不做多协议推送适配。
* 只处理第一阶段必须的对外通知基础能力。

---

## 5. 关键业务规则草案 (Business Rule Draft)

* 外部渠道只能通过 Channel Gateway 调用 Headless contract，不得直连内部模块接口。
* 每个渠道请求必须能被识别为明确的 channel identity，并通过基础鉴权校验。
* 对外 contract 以稳定资源语义为主，不直接暴露内部实现细节或临时状态对象。
* 对外返回的订单与履约状态必须遵守前两个 Epic 已确认的状态语义，不得重新发明另一套状态机。
* callback / webhook 必须具备可验证来源、明确投递状态和可追踪投递记录。
* 所有对外关键交互都必须具备基础 Audit Trail，便于排障和对账。

---

## 6. Decision Matrix

以下问题已完成业务侧拍板，后续 RFC 与设计阶段应严格以这些结论为前提：

| ID | Ambiguity / Decision Point | Option A | Option B | Final Decision |
| :- | :------------------------- | :------- | :------- | :------------- |
| 1 | 渠道鉴权基线 | 静态 `API Key` / `Secret`。优势: 实现快、集成成本低；劣势: 安全治理能力较弱。 | 签名请求或 token-based 模型。优势: 更安全、更接近开放平台；劣势: 第一阶段实现与接入成本更高。 | 选项 A |
| 2 | 对外 contract 风格 | 先采用 REST 资源风格。优势: 易理解、易对接；劣势: 某些复杂查询表达力有限。 | 先采用 command/query 混合风格。优势: 更贴合交易动作语义；劣势: contract 一致性更难控。 | 组合策略：查询使用资源风格，状态推进使用 command 风格 |
| 3 | Webhook / callback 投递基线 | 仅保留出站 callback 记录，失败后人工补发。优势: 简单；劣势: 自动恢复弱。 | 提供基础自动重试与投递留痕。优势: 更可用；劣势: 需要额外调度与状态管理。 | 选项 B，但限定为基础版：通知任务独立记录、明确投递状态、有限次自动重试、每次尝试留痕、超过阈值后进入人工补发/人工处理 |
| 4 | 查询接口暴露范围 | 先只暴露基础查询字段与最小分页过滤。优势: contract 稳定、收敛快；劣势: 首批渠道灵活性有限。 | 一次性暴露更丰富筛选和明细字段。优势: 渠道能力更强；劣势: 容易把内部模型细节泄漏出去。 | 选项 A |

---

## 7. 进入下阶段的准入条件 (Readiness Gate)

进入第一个 RFC 前，建议至少确认以下事项：

* 接受本 Epic 的 3 段式切片，不把接入鉴权、查询 contract、通知 contract 一次性塞进单个 RFC。
* 后续设计必须遵守已确认的 4 项决策，尤其是静态 `API Key` / `Secret`、查询与状态推进分风格建模、轻量版通知自动重试、最小查询暴露范围。
* 明确第一阶段以“统一 contract、最小可接入、安全边界清晰”为优先，而不是追求开放平台能力最大化。

---

## 8. 已确认设计前提 (Confirmed Design Premises)

* 渠道鉴权第一阶段采用静态 `API Key` / `Secret` 作为基础模型，不引入更重的签名或 token 体系。
* 对外 contract 采用混合风格：查询能力使用资源风格，状态推进能力使用 command 风格。
* Webhook / callback 投递采用基础版自动重试模型：通知任务独立记录、具备明确投递状态、有限次自动重试、每次尝试留痕，超过阈值后进入人工补发或人工处理。
* 查询接口第一阶段只暴露基础字段与最小分页/过滤能力，不扩展为富筛选或复杂明细接口。
