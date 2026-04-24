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
| `Fulfillment` | fulfillment | Payment Confirmation 成功后创建的履约投影 | 一个 Order 物理约束只生成一个 Fulfillment |
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
  status: "PENDING" | "PROCESSING" | "SUCCEEDED" | "FAILED";
  paymentProvider: string;
  providerEventId: string;         // 渠道事件 ID，用于回调排障
  providerPaymentId: string?;      // 渠道支付单号，若提供则落库
  confirmedAt: DateTime;           // 业务确认时间
  currentAttemptId: string?;       // status = PROCESSING 时必填
  processingStartedAt: DateTime?;  // status = PROCESSING 时必填
  terminalAt: DateTime?;           // status in SUCCEEDED | FAILED 时必填
  executionPath: "DEFAULT_PROVIDER";
  deliveryResult: DeliveryResult?; // status = SUCCEEDED 时必填
  lastFailure: FailureSummary?;    // status = FAILED 时必填
  lastDiagnosticTrace: DiagnosticTrace;
  channelContext: Map<string, string>?;
  version: long;                   // claim / terminal 提交统一使用 CAS
  createdAt: DateTime;
  updatedAt: DateTime;
}

interface FulfillmentAttempt {
  attemptId: string;
  fulfillmentId: string;           // N:1 to Fulfillment
  attemptNo: int;                  // Stage A 冻结为 1
  status: "EXECUTING" | "SUCCEEDED" | "FAILED";
  dispatcherRunId: string;
  executorRef: string;
  executionPath: "DEFAULT_PROVIDER";
  claimedAt: DateTime;
  startedAt: DateTime?;
  finishedAt: DateTime?;
  deliveryResult: DeliveryResult?;
  failure: FailureSummary?;
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
- `Fulfillment (1) -> (N) FulfillmentAttempt` 为未来扩展预留，但 Stage A 只允许一个有效执行尝试，`attemptNo = 1`。
- `status = PROCESSING` 时，必须存在且仅存在一个 `currentAttemptId` 指向 `status = EXECUTING` 的 attempt。
- `status = SUCCEEDED` 时，`deliveryResult` 与 `terminalAt` 必填，`lastFailure` 必须为空。
- `status = FAILED` 时，`lastFailure` 与 `terminalAt` 必填，`deliveryResult` 必须为空。
- `status in {SUCCEEDED, FAILED}` 时，终态不可逆；不得再迁回 `PENDING` 或 `PROCESSING`。
- 只有与 `currentAttemptId` 匹配的 attempt 才有资格提交成功或失败终局。
- `PROCESSING -> PROCESSING` 仅允许记录保守滞留诊断；不得释放执行权、不得伪造成功/失败终局、不得回退到 `PENDING`。
- `executionPath` 在 Stage A 固定为 `DEFAULT_PROVIDER`，不得引入 provider route、fallback path 或多实现编排。
- `lastDiagnosticTrace` 为治理必填字段；空值视为 invariant broken。

### 2.6 `AuditTrailEvent`

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

### 2.7 `IdempotencyRecord`（基础设施）

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
                              │ owns
                              ▼
                      FulfillmentAttempt (0..N)
     │
     ▼                        ▼
  AuditTrailEvent (append-only, 引用所有聚合的 id)
```

**关键约束:**
- `Reservation -> TicketOrder`：严格 1:1；不允许正常主链路绕过 Reservation 直接创建 Order。
- `TicketOrder -> Fulfillment`：严格 1:1；由 `fulfillment_record.order_id` 唯一约束兜底。
- `Fulfillment -> FulfillmentAttempt`：Stage A 逻辑上允许 `0..N`，但冻结为最多 1 个有效 attempt；后续多次尝试需新 feat 明确扩展。
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

---

## 5. Provenance（追溯）

| 实体 | 首次引入 | 演进 |
|---|---|---|
| `CatalogItem` / `InventoryResource` / `Reservation` / `TicketOrder` / `IdempotencyRecord` | feat-TKT001-01 | — |
| `TicketOrder.confirmedAt` / `TicketOrder.version` | feat-TKT001-02 | ALTER TABLE |
| `Fulfillment` | feat-TKT001-02 | feat-TKT002-01 扩展到 `PROCESSING / SUCCEEDED / FAILED` 并引入 execution metadata |
| `FulfillmentAttempt` / `DeliveryResult` / `FailureSummary` / `DiagnosticTrace` | feat-TKT002-01 | — |
| `TicketOrder.closedAt` / `Reservation.releasedAt` | feat-TKT001-03 | ALTER TABLE |
| `AuditTrailEvent` | feat-TKT001-03 | — |
