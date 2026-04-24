# Domain Models — Living Architecture

**Role:** 全局唯一真相源。记录已落地的聚合根、实体、值对象与其不变量。
**Maintained by:** librarian（每次 feature 合入后增量更新）
**Scope:** 仅登记 **已通过 Reviewer 终审并合入主干** 的内容；未落地的规划不进入本文件。

---

## 1. 聚合根总览

| Aggregate Root | 所属模块 | 职责 | 关键不变量 |
|---|---|---|---|
| `CatalogItem` | catalog | 渠道可见商品与底层库存资源的绑定 | 一个 catalog item 只绑定一个 inventory resource |
| `InventoryResource` | inventory | 库存总量、已预留量、并发控制基线 | `sellable_quantity = total_quantity - reserved_quantity`（应用层投影，不存储） |
| `Reservation` | reservation | 锁票凭证，承载 TTL 与 consume 一次性约束 | 同一 Reservation 仅可被 consume 一次；三态 `ACTIVE / CONSUMED / EXPIRED` |
| `TicketOrder` | order | 由 Reservation 派生的 Order 主记录 | 一个 Reservation 物理约束只派生一个 Order；三态 `PENDING_PAYMENT / CONFIRMED / CLOSED` |
| `Fulfillment` | fulfillment | Payment Confirmation 成功后创建的履约与治理聚合 | 一个 Order 物理约束只生成一个 Fulfillment；自动重试与人工接管都围绕该聚合收敛 |
| `AuditTrailEvent` | audit | append-only 审计事件（关键状态变化 + 拒绝结果） | 不可变、不可删，只记录已提交业务事实 |
| `IdempotencyRecord` | infrastructure | 动作级幂等命中结果与冲突检测 | `(action_name, idempotency_key)` 唯一；三态 `PROCESSING / SUCCEEDED / FAILED` |

---

## 2. 聚合根定义（伪代码 TypeScript interface 风格）

### 2.1 `CatalogItem`

```typescript
interface CatalogItem {
  catalogItemId: string;           // PK, 渠道侧商品标识符
  inventoryResourceId: string;     // UK, 绑定的库存资源
  name: string;
  status: "DRAFT" | "ACTIVE" | "OFF_SHELF";
  version: long;                   // 乐观锁
  createdAt: DateTime;
  updatedAt: DateTime;
}
```

**规则:**
- 只有 `status = ACTIVE` 的 catalog item 才允许参与锁票前的可售校验。
- `inventoryResourceId` 唯一约束确保一个库存资源不被多个 catalog item 重复占用。

### 2.2 `InventoryResource`

```typescript
interface InventoryResource {
  inventoryResourceId: string;     // PK
  resourceCode: string;            // UK, 人类可读编码
  totalQuantity: int;              // >= 0
  reservedQuantity: int;           // >= 0
  status: "ACTIVE" | "FROZEN" | "OFFLINE";
  version: long;                   // 乐观锁，扣减核心字段
  createdAt: DateTime;
  updatedAt: DateTime;
}

// 应用层投影（不存储）
sellableQuantity = totalQuantity - reservedQuantity;
```

**规则:**
- `reserved_quantity` 扣减 / 回补必须走乐观锁（`version` CAS），不得直接改写。
- 只有 `status = ACTIVE` 的库存资源允许进入 Reservation 创建路径。
- 超时释放路径中，`reserved_quantity` 由 Reservation 的 `quantity` 回补。

### 2.3 `Reservation`

```typescript
interface Reservation {
  reservationId: string;           // PK
  externalTradeNo: string;         // 整笔交易关联键
  catalogItemId: string;
  inventoryResourceId: string;
  quantity: int;                   // >= 1
  status: "ACTIVE" | "CONSUMED" | "EXPIRED";
  expiresAt: DateTime;             // 由 reservation_ttl_seconds 推导，TTL 截止点
  consumedOrderId: string?;        // UK（仅 non-null 参与唯一性）, consume 时写入
  consumedAt: DateTime?;
  releasedAt: DateTime?;           // 仅 timeout release 路径填充；区别于 expiresAt
  channelContext: Map<string, string>?;
  version: long;                   // 乐观锁，保护 ACTIVE->CONSUMED 唯一性
  createdAt: DateTime;
  updatedAt: DateTime;
}
```

**规则:**
- 三态模型：`ACTIVE -> CONSUMED`（Order 创建时）/ `ACTIVE -> EXPIRED`（TTL 到期）/ `CONSUMED -> EXPIRED`（Order timeout release）。
- **不引入第四个 `RELEASED` 状态**；释放原因由 `AuditTrailEvent.reason_code` 区分（`RESERVATION_TTL_EXPIRED` vs `ORDER_PAYMENT_TIMEOUT`）。
- `expiresAt` 是 TTL 截止点，**不等于** 实际释放时间；后者存 `releasedAt`。
- `consumedOrderId` 唯一约束物理保证一个 Reservation 只被 consume 一次。

### 2.4 `TicketOrder`

```typescript
interface TicketOrder {
  orderId: string;                 // PK
  externalTradeNo: string;         // UK
  reservationId: string;           // UK, 物理保证一个 Reservation 只落一个 Order
  status: "PENDING_PAYMENT" | "CONFIRMED" | "CLOSED";
  buyerRef: string;
  contactPhone: string?;
  contactEmail: string?;
  submissionContext: Map<string, string>?;
  paymentDeadlineAt: DateTime;     // 超时关闭扫描入口
  confirmedAt: DateTime?;          // 进入 CONFIRMED 时写入
  closedAt: DateTime?;             // 进入 CLOSED 时写入（仅 timeout 来源）
  version: long;                   // 乐观锁，支付确认与关闭竞争裁决
  createdAt: DateTime;
  updatedAt: DateTime;
}
```

**规则:**
- `PENDING_PAYMENT` 是唯一的初始状态，必须由 Reservation consume 派生而来。
- 终态分叉：`CONFIRMED`（valid payment confirmation）/ `CLOSED`（timeout release）。
- **单一状态裁决聚合**：同一 Order 只允许一个 `PENDING_PAYMENT -> terminal` 转移提交成功（乐观锁兜底）。
- `CLOSED` 目前唯一来源是 timeout，故不持久化 `closeReasonCode`；原因由 `AuditTrailEvent` 承载。

### 2.5 `Fulfillment`

```typescript
interface Fulfillment {
  fulfillmentId: string;           // PK
  orderId: string;                 // UK, 物理保证一单一履约
  status: "PENDING" | "PROCESSING" | "RETRY_PENDING" | "MANUAL_PENDING" | "SUCCEEDED" | "FAILED";
  paymentProvider: string;
  providerEventId: string;         // 渠道事件 ID，用于回调排障
  providerPaymentId: string?;      // 渠道支付单号，若提供则落库
  confirmedAt: DateTime;           // 业务确认时间
  currentAttemptId: string?;       // status = PROCESSING 时必填
  latestAttemptId: string?;        // 最近一次 attempt（包括已收口历史 attempt）
  processingStartedAt: DateTime?;  // status = PROCESSING 时必填
  processingTimeoutAt: DateTime?;  // status = PROCESSING 时必填，治理 lease 截止点
  terminalAt: DateTime?;           // status in SUCCEEDED | FAILED 时必填
  executionPath: "DEFAULT_PROVIDER";
  deliveryResult: DeliveryResult?; // status = SUCCEEDED 时必填
  lastFailure: FailureSummary?;    // Stage A 失败摘要兼容字段
  lastDiagnosticTrace: DiagnosticTrace;
  retryPolicy: RetryPolicySnapshot;
  retryState: RetryState;
  latestFailure: FailureDecision?;
  channelContext: Map<string, string>?;
  version: long;                   // claim / terminal 提交统一使用 CAS
  createdAt: DateTime;
  updatedAt: DateTime;
}

interface RetryPolicySnapshot {
  fastRetryLimit: int;
  backoffRetryLimit: int;
  totalRetryBudget: int;
  backoffSchedule: Duration[];
}

interface RetryState {
  fastRetryUsed: int;
  backoffRetryUsed: int;
  totalRetryUsed: int;
  nextRetryAt: DateTime?;
  budgetExhausted: boolean;
}

interface ProcessingLease {
  processingStartedAt: DateTime;
  timeoutAt: DateTime;
}

interface FulfillmentAttempt {
  attemptId: string;
  fulfillmentId: string;           // N:1 to Fulfillment
  attemptNo: int;                  // Stage B 起允许递增重试 attempt
  trigger: "INITIAL_EXECUTION" | "FAST_RETRY" | "BACKOFF_RETRY" | "PROCESSING_TIMEOUT_GOVERNANCE";
  executionStatus: "STARTED" | "SUCCEEDED" | "FAILED_CLASSIFIED" | "ABANDONED";
  status: "EXECUTING" | "SUCCEEDED" | "FAILED" | "ABANDONED"; // 兼容 Stage A 旧列
  dispatcherRunId: string;
  executorRef: string;
  executionPath: "DEFAULT_PROVIDER";
  claimedAt: DateTime;
  startedAt: DateTime?;
  finishedAt: DateTime?;
  deliveryResult: DeliveryResult?;
  failure: FailureSummary?;         // Stage A 失败摘要兼容字段
  failureDecision: FailureDecision?;
  providerDiagnostic: ProviderDiagnostic?;
  diagnosticTrace: DiagnosticTrace;
}

interface DeliveryResult {
  resourceType: string;
  resourceId: string;
  payloadSummary: Map<string, string | number | boolean>;
  deliveredAt: DateTime;
}

interface FailureSummary {
  reasonCode: "PROVIDER_REJECTED" | "PROVIDER_TIMEOUT" | "PROVIDER_TECHNICAL_FAILURE" | "DELIVERY_RESULT_INVALID";
  reasonMessage: string;           // concise stable summary, not raw stack trace
  failedAt: DateTime;
}

interface FailureDecision {
  category:
    | "RETRYABLE_TECHNICAL_FAILURE"
    | "FINAL_BUSINESS_REJECTED"
    | "MANUAL_REVIEW_REQUIRED"
    | "UNCERTAIN_RESULT";
  reasonCode:
    | "NETWORK_TIMEOUT"
    | "GATEWAY_TEMPORARY_ERROR"
    | "PROVIDER_RATE_LIMITED"
    | "PROVIDER_TEMPORARILY_UNAVAILABLE"
    | "FULFILLMENT_WINDOW_EXPIRED"
    | "ORDER_CONDITION_INVALID"
    | "PROVIDER_PERMANENT_REJECTED"
    | "UPSTREAM_DATA_REQUIRES_REVIEW"
    | "MANUAL_SOURCE_SWITCH_REQUIRED"
    | "EXTERNAL_RESULT_UNKNOWN"
    | "PROCESSING_STUCK_SAFE_TO_RETRY"
    | "PROCESSING_STUCK_UNSAFE_TO_RETRY";
  retryDisposition:
    | "ALLOW_FAST_RETRY"
    | "ALLOW_BACKOFF_RETRY"
    | "STOP_AND_MANUAL"
    | "STOP_AND_FINAL_FAIL";
  manualReviewRequired: boolean;
  finalTerminationSuggested: boolean;
  rationale: string;
  classifiedAt: DateTime;
}

interface ProviderDiagnostic {
  providerCode: string?;
  providerMessage: string?;
  rawOutcomeKnown: boolean;
}

interface DiagnosticTrace {
  traceId: string;
  decision:
    | "DISPATCH_CANDIDATE_FOUND"
    | "CLAIM_ACCEPTED"
    | "CLAIM_REJECTED"
    | "EXECUTION_STARTED"
    | "PROVIDER_CALL_SUCCEEDED"
    | "PROVIDER_CALL_FAILED"
    | "RESULT_COMMITTED"
    | "RESULT_LEFT_PROCESSING";
  providerCorrelationKey: string?;
  externalCallRef: string?;
  errorDetailSummary: string?;
  observedAt: DateTime;
  tags: Map<string, string>;
}
```

**规则:**
- 在 Payment Confirmation 成功事务内创建初始 `Fulfillment.PENDING`，与 `Order.CONFIRMED` 位于同一本地事务。
- `orderId` 唯一约束**物理兜底** "一单一履约"；**不为 providerEventId 加唯一约束**（它只承担 traceability，不替代动作级幂等）。
- `Fulfillment (1) -> (N) FulfillmentAttempt` 已在 Stage B 落地为多次治理尝试模型，但任一时刻只允许一个活跃 attempt 被 `currentAttemptId` 持有。
- `status = PROCESSING` 时，必须存在且仅存在一个 `currentAttemptId` 指向 `status = EXECUTING` 的 attempt。
- `status = RETRY_PENDING` 时，不存在活跃 attempt；下一步只能由治理侧显式调度 `FAST_RETRY` / `BACKOFF_RETRY` 重新进入 `PROCESSING`。
- `status = MANUAL_PENDING` 表示自动恢复已停止，等待人工接管；不是终态，但自动调度不得再自行推进。
- `status = SUCCEEDED` 时，`deliveryResult` 与 `terminalAt` 必填，`lastFailure` 必须为空。
- `status = FAILED` 表示人工治理后的最终失败终局；`terminalAt` 必填，`deliveryResult` 必须为空。
- `status in {SUCCEEDED, FAILED}` 时，终态不可逆；不得再迁回 `PENDING`、`RETRY_PENDING` 或 `PROCESSING`。
- 只有与 `currentAttemptId` 匹配的 attempt 才有资格提交成功或失败终局。
- `PROCESSING` 超时治理若判定可安全重试，必须先把当前 attempt 收口为 `ABANDONED`，再进入 `RETRY_PENDING`；若证据不足，则进入 `MANUAL_PENDING`。
- `executionPath` 在 Stage A 固定为 `DEFAULT_PROVIDER`，不得引入 provider route、fallback path 或多实现编排。
- `lastDiagnosticTrace` 为治理必填字段；空值视为 invariant broken。

### 2.6 `GovernanceAuditRecord`

```typescript
interface GovernanceAuditRecord {
  auditId: string;                 // PK
  fulfillmentId: string;
  attemptId: string?;
  actionType:
    | "ATTEMPT_STARTED"
    | "ATTEMPT_ABANDONED"
    | "ATTEMPT_CLASSIFIED"
    | "FAST_RETRY_SCHEDULED"
    | "BACKOFF_RETRY_SCHEDULED"
    | "MOVED_TO_RETRY_PENDING"
    | "MOVED_TO_MANUAL_PENDING"
    | "MOVED_TO_FAILED"
    | "MOVED_TO_SUCCEEDED"
    | "PROCESSING_TIMEOUT_GOVERNED";
  fromStatus: Fulfillment["status"]?;
  toStatus: Fulfillment["status"]?;
  failureCategory: FailureDecision["category"]?;
  reasonCode: FailureDecision["reasonCode"]?;
  retryBudgetSnapshot: RetryState?;
  actorType: "SYSTEM";
  occurredAt: DateTime;
  createdAt: DateTime;
}
```

**规则:**
- `FulfillmentGovernanceAuditRecord` 是履约治理专用 append-only 证据表，补充通用 `AuditTrailEvent` 无法表达的治理细粒度上下文。
- 预算快照记录的是动作发生后的 `RetryState` 结果，用于人工排障与运营追责。
- Stage B actor 冻结为 `SYSTEM`；人工工作台接入需后续 feature 明确定义。

### 2.7 `AuditTrailEvent`

```typescript
interface AuditTrailEvent {
  eventId: string;                 // PK
  eventType: EventType;
  aggregateType: "RESERVATION" | "ORDER" | "PAYMENT_CONFIRMATION" | "FULFILLMENT" | "INVENTORY_RESOURCE";
  aggregateId: string;
  externalTradeNo: string;         // 关联键
  orderId: string?;
  reservationId: string?;
  inventoryResourceId: string?;
  fulfillmentId: string?;
  providerEventId: string?;
  requestId: string?;
  idempotencyKey: string?;
  actorType: "SYSTEM" | "CHANNEL" | "OPERATOR";
  actorRef: string?;
  reasonCode: ReasonCode;
  payloadSummary: Map<string, string | number | boolean>?;
  occurredAt: DateTime;            // 业务事实发生时间
  createdAt: DateTime;             // 审计行落库时间
}

type EventType =
  | "RESERVATION_CREATED" | "RESERVATION_CONSUMED" | "RESERVATION_RELEASED"
  | "ORDER_CREATED" | "ORDER_CONFIRMED" | "ORDER_TIMEOUT_CLOSED"
  | "PAYMENT_CONFIRMATION_APPLIED" | "PAYMENT_CONFIRMATION_REPLAYED" | "PAYMENT_CONFIRMATION_REJECTED"
  | "FULFILLMENT_CREATED"
  | "FULFILLMENT_DISPATCH_CANDIDATE_FOUND"
  | "FULFILLMENT_PROCESSING_STARTED"
  | "FULFILLMENT_RESULT_LEFT_PROCESSING"
  | "FULFILLMENT_SUCCEEDED"
  | "FULFILLMENT_FAILED"
  | "INVENTORY_RESTORED";

type ReasonCode =
  | "ORDER_PAYMENT_TIMEOUT" | "RESERVATION_TTL_EXPIRED"
  | "PAYMENT_CONFIRMED" | "PAYMENT_REPLAYED"
  | "CLOSED_BY_TIMEOUT" | "MANUAL_ACTION" | "NOT_APPLICABLE"
  | "DISPATCH_SCAN_MATCHED" | "PROCESSING_CLAIMED"
  | "RESULT_UNCERTAIN_LEFT_PROCESSING"
  | "DELIVERY_COMPLETED" | "DELIVERY_FAILED";
```

**规则:**
- **append-only**：不可更新、不可删除。
- 不持久化完整敏感 payload，仅保留 `payloadSummary` 中的关联键与非敏感摘要。
- `occurredAt` 与 `createdAt` 分离，应对 callback 重放、事务重试的时序分析。
- 不对 `providerEventId` / `requestId` / `idempotencyKey` 加唯一约束（traceability 用途，不替代幂等裁决）。

### 2.8 `IdempotencyRecord`（基础设施）

```typescript
interface IdempotencyRecord {
  idempotencyRecordId: string;     // PK
  actionName: string;              // UK(actionName, idempotencyKey)
  idempotencyKey: string;          // UK
  requestHash: string;             // SHA-256, 用于冲突检测
  externalTradeNo: string;
  resourceType: string?;           // SUCCEEDED 后写入
  resourceId: string?;             // SUCCEEDED 后写入
  status: "PROCESSING" | "SUCCEEDED" | "FAILED";
  responsePayload: JSON?;          // 缓存成功响应，用于 replay
  createdAt: DateTime;
  updatedAt: DateTime;
}
```

**规则:**
- **动作级幂等隔离**：`actionName` 枚举包括 `CREATE_RESERVATION` / `CREATE_ORDER` / `PAYMENT_CONFIRMATION`；不同动作不共享幂等空间。
- 同 key 不同 `requestHash` 返回 `IDEMPOTENCY_CONFLICT`，**不得** 复用旧结果。
- `resourceType` 允许为 null（支持 `PROCESSING` 状态在 resource 确定前插入，由 V1.0.1 migration 确认）。

---

## 3. 聚合间关系

```
CatalogItem (1) ─── (1) InventoryResource
                          ▲
                          │ reserves / restores quantity
                          │
Reservation (N) ─────────(1) InventoryResource     [lock / release]
     │
     │ consume (1:1)
     ▼
TicketOrder (1) ─── (0..1) Fulfillment             [一单一履约]
                              │
                              ├── owns ──► FulfillmentAttempt (0..N)
                              │
                              └── emits ─► GovernanceAuditRecord (0..N)
     │
     ▼
  AuditTrailEvent (append-only, 引用所有聚合的 id)
```

**关键约束:**
- `Reservation -> TicketOrder`：严格 1:1；不允许正常主链路绕过 Reservation 直接创建 Order。
- `TicketOrder -> Fulfillment`：严格 1:1；由 `fulfillment_record.order_id` 唯一约束兜底。
- `Fulfillment -> FulfillmentAttempt`：Stage B 已允许历史 attempts 递增累积，但同一时刻最多 1 个活跃 attempt。
- `Fulfillment -> GovernanceAuditRecord`：严格 append-only；治理动作成功提交时必须同事务落一条或多条对应审计。
- `AuditTrailEvent`：旁路关联，不参与事务一致性裁决，但 timeout close 路径中的 append 必须与主状态迁移同事务。

---

## 4. 跨聚合事务边界

### 4.1 Reservation + Order 创建（RFC-TKT001-01）

同一本地事务内：
1. 校验 Reservation `ACTIVE` 状态与 TTL。
2. Reservation `ACTIVE -> CONSUMED`（乐观锁兜底）。
3. Order 落库（初始 `PENDING_PAYMENT`）。

任一失败整体回滚；不接受 "Reservation 已 consume 但 Order 缺失" 的悬空。

### 4.2 Payment Confirmation（RFC-TKT001-02）

同一本地事务内：
1. 按 `externalTradeNo` 加载 Order 并校验 `PENDING_PAYMENT`。
2. Order `PENDING_PAYMENT -> CONFIRMED`（乐观锁兜底）。
3. 创建 Fulfillment（`orderId` 唯一约束兜底）。
4. `IdempotencyRecord` 标记 `SUCCEEDED` 并缓存 response。

若 Order 已 `CONFIRMED`：走 replay 路径，返回稳定 `fulfillmentId`，视为 `REPLAYED` 成功。

### 4.3 Timeout Close（RFC-TKT001-03）

同一本地事务内：
1. 重新加载 Order 并验证 `PENDING_PAYMENT && paymentDeadlineAt <= now`。
2. Order `PENDING_PAYMENT -> CLOSED`，写 `closedAt`。
3. Reservation `CONSUMED -> EXPIRED`，写 `releasedAt`。
4. InventoryResource 回补 `reservedQuantity`（乐观锁 CAS）。
5. append `ORDER_TIMEOUT_CLOSED` / `RESERVATION_RELEASED` / `INVENTORY_RESTORED` 三条 audit event。

**任一子步骤失败 → 整笔回滚**，等下一轮扫描重试。不接受 "先关闭再异步补偿" 的降级。

### 4.4 Fulfillment Claim / Terminal Commit（feat-TKT002-01）

Stage A 的 Fulfillment Processing 由以下本地事务组成：

1. T4 Claim：`Fulfillment.PENDING -> PROCESSING`，创建 `FulfillmentAttempt(EXECUTING)`，写入 `currentAttemptId`、`processingStartedAt`、`lastDiagnosticTrace`，并 append `FULFILLMENT_PROCESSING_STARTED`。
2. T5 Left Processing：`Fulfillment.PROCESSING -> PROCESSING`，仅更新 `lastDiagnosticTrace` / attempt 诊断信息，并 append `FULFILLMENT_RESULT_LEFT_PROCESSING`。
3. T6 Success：`Fulfillment.PROCESSING -> SUCCEEDED`，同时提交 `DeliveryResult`、`terminalAt`、attempt 成功状态，并 append `FULFILLMENT_SUCCEEDED`。
4. T7 Failure：`Fulfillment.PROCESSING -> FAILED`，同时提交 `FailureSummary`、`terminalAt`、attempt 失败状态，并 append `FULFILLMENT_FAILED`。

统一约束：
1. 所有事务都以 `fulfillment.version` CAS 作为唯一仲裁，且成功/失败/保守滞留都必须校验 `attemptId == currentAttemptId`。
2. 不承诺外部 provider 调用与本地终局提交的分布式原子性；若结果已发生但本地无法安全定终局，必须走 T5 保守滞留。
3. 不接受主状态成功但摘要或审计缺失；任一子步骤失败整笔回滚。

### 4.5 Fulfillment Governance Convergence（feat-TKT002-02）

Stage B 在 `Fulfillment` 聚合内新增治理事务：

1. T8 `ClassifyAttemptFailure`：`PROCESSING` 中的活跃 attempt 被分类，attempt 写入 `failureDecision` / `providerDiagnostic` 并收口为 `FAILED_CLASSIFIED`，aggregate 收敛到 `RETRY_PENDING` / `MANUAL_PENDING` / `FAILED`。
2. T9 `ScheduleRetryAfterFailure`：仅在 `RETRY_PENDING` 下消耗预算并安排 `FAST_RETRY` 或 `BACKOFF_RETRY`；预算耗尽时成功收敛到 `MANUAL_PENDING`，不再对外暴露失败。
3. T10 `StartRetryAttempt`：从 `RETRY_PENDING` 创建新 attempt，并重新进入 `PROCESSING`，同时设置新的 processing lease。
4. T11 `GovernProcessingTimeout`：对超时的 `PROCESSING` 对象执行治理，把活跃 attempt 改写为 `ABANDONED`，再根据证据进入 `RETRY_PENDING` 或 `MANUAL_PENDING`。
5. T12 `RecordAttemptSuccess`：当前活跃 attempt 成功收口，aggregate 进入 `SUCCEEDED`。

统一约束：
1. `T8-T12` 全部以 `fulfillment.version` CAS 为唯一串行化裁决，不引入跨聚合分布式事务。
2. 治理状态迁移、attempt 收口、`retry_state_json` 改写与 `fulfillment_governance_audit_record` append 必须同事务提交。
3. `RETRY_PENDING` / `MANUAL_PENDING` 下 `currentAttemptId` 与 processing lease 必须为空，避免“无活跃执行却仍持有 lease”的脏态。

---

## 5. Provenance（追溯）

| 实体 | 首次引入 | 演进 |
|---|---|---|
| `CatalogItem` / `InventoryResource` / `Reservation` / `TicketOrder` / `IdempotencyRecord` | feat-TKT001-01 | — |
| `TicketOrder.confirmedAt` / `TicketOrder.version` | feat-TKT001-02 | ALTER TABLE |
| `Fulfillment` | feat-TKT001-02 | feat-TKT002-01 扩展到 `PROCESSING / SUCCEEDED / FAILED`；feat-TKT002-02 再扩展到 `RETRY_PENDING / MANUAL_PENDING` 并引入治理元数据 |
| `FulfillmentAttempt` / `DeliveryResult` / `FailureSummary` / `DiagnosticTrace` | feat-TKT002-01 | feat-TKT002-02 增加 `trigger` / `executionStatus` / `FailureDecision` / `ProviderDiagnostic` |
| `RetryPolicySnapshot` / `RetryState` / `ProcessingLease` / `GovernanceAuditRecord` | feat-TKT002-02 | — |
| `TicketOrder.closedAt` / `Reservation.releasedAt` | feat-TKT001-03 | ALTER TABLE |
| `AuditTrailEvent` | feat-TKT001-03 | — |
