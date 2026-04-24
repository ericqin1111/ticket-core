# API Contract — Fulfillment

**Maintained by:** librarian
**Last updated by:** migration (from feat-TKT002-01 blueprint / implementation)
**Owning aggregate:** `Fulfillment`（参见 `domain_models.md §2.5`）
**Related state machine:** `global_state_machine.md §3 / §4`

---

## Query 1: ListDispatchableFulfillments

**Introduced by:** `feat-TKT002-01`
**Purpose:** 按稳定排序扫描可接管的 `Fulfillment.PENDING` 候选。
**Idempotency:** 幂等查询，无 `Idempotency-Key` 要求。

### Request — `list-dispatchable-fulfillments-request`

```json
{
  "$id": "list-dispatchable-fulfillments-request",
  "type": "object",
  "required": ["scan_id", "batch_size", "ordered_by"],
  "properties": {
    "scan_id":     { "type": "string", "minLength": 1 },
    "batch_size":  { "type": "integer", "minimum": 1 },
    "cursor":      { "type": "string" },
    "ordered_by":  { "type": "string", "enum": ["created_at"] }
  }
}
```

### Response 200 — `list-dispatchable-fulfillments-response`

```json
{
  "$id": "list-dispatchable-fulfillments-response",
  "type": "object",
  "required": ["candidates"],
  "properties": {
    "candidates": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["fulfillment_id", "order_id", "status", "created_at", "version"],
        "properties": {
          "fulfillment_id": { "type": "string" },
          "order_id":       { "type": "string" },
          "status":         { "type": "string", "enum": ["PENDING"] },
          "created_at":     { "type": "string", "format": "date-time" },
          "version":        { "type": "integer" }
        }
      }
    },
    "next_cursor": { "type": "string" }
  }
}
```

**Field notes**

* 仅返回 `status = PENDING` 的候选。
* 稳定排序键冻结为 `created_at`；实现可使用复合 cursor 保证同时间戳下无重复/漏扫，但不得改变对外 contract 字段。
* 成功返回中的每个 candidate，都必须 append 一条 `FULFILLMENT_DISPATCH_CANDIDATE_FOUND` 审计事件。
* 该审计事件最小字段应包含 `scan_id`、`fulfillment_id`、`order_id`、`occurred_at` 与 `decision = DISPATCH_CANDIDATE_FOUND`。

### Error Responses

无业务错误；空结果是合法响应。

---

## Query 2: GetFulfillmentExecutionSnapshot

**Introduced by:** `feat-TKT002-01`
**Purpose:** 返回履约执行快照，用于治理、排障与人工确认。
**Idempotency:** 幂等查询，无 `Idempotency-Key` 要求。

### Request — `get-fulfillment-execution-snapshot-request`

```json
{
  "$id": "get-fulfillment-execution-snapshot-request",
  "type": "object",
  "required": ["fulfillment_id"],
  "properties": {
    "fulfillment_id": { "type": "string", "minLength": 1 }
  }
}
```

### Response 200 — `get-fulfillment-execution-snapshot-response`

```json
{
  "$id": "get-fulfillment-execution-snapshot-response",
  "type": "object",
  "required": ["fulfillment", "attempts"],
  "properties": {
    "fulfillment": { "$ref": "#/$defs/fulfillment" },
    "attempts": {
      "type": "array",
      "items": { "$ref": "#/$defs/fulfillment_attempt" }
    }
  },
  "$defs": {
    "fulfillment": {
      "type": "object",
      "required": [
        "fulfillment_id",
        "order_id",
        "status",
        "execution_path",
        "last_diagnostic_trace",
        "version",
        "created_at",
        "updated_at"
      ],
      "properties": {
        "fulfillment_id":        { "type": "string" },
        "order_id":              { "type": "string" },
        "status":                { "type": "string", "enum": ["PENDING", "PROCESSING", "SUCCEEDED", "FAILED"] },
        "current_attempt_id":    { "type": "string" },
        "processing_started_at": { "type": "string", "format": "date-time" },
        "terminal_at":           { "type": "string", "format": "date-time" },
        "execution_path":        { "type": "string", "enum": ["DEFAULT_PROVIDER"] },
        "delivery_result":       { "$ref": "#/$defs/delivery_result" },
        "last_failure":          { "$ref": "#/$defs/failure_summary" },
        "last_diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" },
        "version":               { "type": "integer" },
        "created_at":            { "type": "string", "format": "date-time" },
        "updated_at":            { "type": "string", "format": "date-time" }
      }
    },
    "fulfillment_attempt": {
      "type": "object",
      "required": [
        "attempt_id",
        "fulfillment_id",
        "attempt_no",
        "status",
        "dispatcher_run_id",
        "executor_ref",
        "execution_path",
        "claimed_at",
        "diagnostic_trace"
      ],
      "properties": {
        "attempt_id":       { "type": "string" },
        "fulfillment_id":   { "type": "string" },
        "attempt_no":       { "type": "integer", "minimum": 1 },
        "status":           { "type": "string", "enum": ["EXECUTING", "SUCCEEDED", "FAILED"] },
        "dispatcher_run_id": { "type": "string" },
        "executor_ref":     { "type": "string" },
        "execution_path":   { "type": "string", "enum": ["DEFAULT_PROVIDER"] },
        "claimed_at":       { "type": "string", "format": "date-time" },
        "started_at":       { "type": "string", "format": "date-time" },
        "finished_at":      { "type": "string", "format": "date-time" },
        "delivery_result":  { "$ref": "#/$defs/delivery_result" },
        "failure":          { "$ref": "#/$defs/failure_summary" },
        "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" }
      }
    },
    "delivery_result": {
      "type": "object",
      "required": ["resource_type", "resource_id", "payload_summary", "delivered_at"],
      "properties": {
        "resource_type":   { "type": "string", "minLength": 1 },
        "resource_id":     { "type": "string", "minLength": 1 },
        "payload_summary": { "type": "object", "additionalProperties": true },
        "delivered_at":    { "type": "string", "format": "date-time" }
      }
    },
    "failure_summary": {
      "type": "object",
      "required": ["reason_code", "reason_message", "failed_at"],
      "properties": {
        "reason_code": {
          "type": "string",
          "enum": ["PROVIDER_REJECTED", "PROVIDER_TIMEOUT", "PROVIDER_TECHNICAL_FAILURE", "DELIVERY_RESULT_INVALID"]
        },
        "reason_message": { "type": "string", "minLength": 1 },
        "failed_at":      { "type": "string", "format": "date-time" }
      }
    },
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":                 { "type": "string", "minLength": 1 },
        "decision": {
          "type": "string",
          "enum": [
            "DISPATCH_CANDIDATE_FOUND",
            "CLAIM_ACCEPTED",
            "CLAIM_REJECTED",
            "EXECUTION_STARTED",
            "PROVIDER_CALL_SUCCEEDED",
            "PROVIDER_CALL_FAILED",
            "RESULT_COMMITTED",
            "RESULT_LEFT_PROCESSING"
          ]
        },
        "provider_correlation_key": { "type": "string" },
        "external_call_ref":        { "type": "string" },
        "error_detail_summary":     { "type": "string" },
        "observed_at":              { "type": "string", "format": "date-time" },
        "tags": {
          "type": "object",
          "additionalProperties": { "type": "string" }
        }
      }
    }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |

详见 `_shared_error_codes.md`。

---

## Query 3: ListStuckProcessingFulfillments

**Introduced by:** `feat-TKT002-01`
**Purpose:** 识别长时间停留在 `PROCESSING` 的对象，作为治理入口。
**Idempotency:** 幂等查询，无 `Idempotency-Key` 要求。

### Request — `list-stuck-processing-fulfillments-request`

```json
{
  "$id": "list-stuck-processing-fulfillments-request",
  "type": "object",
  "required": ["older_than", "batch_size"],
  "properties": {
    "older_than": { "type": "string", "minLength": 1 },
    "batch_size": { "type": "integer", "minimum": 1 }
  }
}
```

### Response 200 — `list-stuck-processing-fulfillments-response`

```json
{
  "$id": "list-stuck-processing-fulfillments-response",
  "type": "object",
  "required": ["items"],
  "properties": {
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "required": [
          "fulfillment_id",
          "order_id",
          "current_attempt_id",
          "processing_started_at",
          "diagnostic_trace"
        ],
        "properties": {
          "fulfillment_id":        { "type": "string" },
          "order_id":              { "type": "string" },
          "current_attempt_id":    { "type": "string" },
          "processing_started_at": { "type": "string", "format": "date-time" },
          "diagnostic_trace":      { "$ref": "#/$defs/diagnostic_trace" }
        }
      }
    }
  },
  "$defs": {
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":     { "type": "string", "minLength": 1 },
        "decision":     { "type": "string" },
        "observed_at":  { "type": "string", "format": "date-time" },
        "tags":         { "type": "object", "additionalProperties": { "type": "string" } }
      }
    }
  }
}
```

### Error Responses

无业务错误；空结果是合法响应。

---

## Command 1: ClaimFulfillmentForProcessing

**Introduced by:** `feat-TKT002-01`
**Purpose:** 对单条 `Fulfillment.PENDING` 发起唯一接管裁决。

### Request — `claim-fulfillment-for-processing-request`

```json
{
  "$id": "claim-fulfillment-for-processing-request",
  "type": "object",
  "required": [
    "fulfillment_id",
    "expected_version",
    "dispatcher_run_id",
    "executor_ref",
    "execution_path",
    "claimed_at",
    "diagnostic_trace"
  ],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "dispatcher_run_id": { "type": "string", "minLength": 1 },
    "executor_ref":     { "type": "string", "minLength": 1 },
    "execution_path":   { "type": "string", "enum": ["DEFAULT_PROVIDER"] },
    "claimed_at":       { "type": "string", "format": "date-time" },
    "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" }
  },
  "$defs": {
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":     { "type": "string", "minLength": 1 },
        "decision":     { "type": "string", "enum": ["CLAIM_ACCEPTED", "CLAIM_REJECTED", "EXECUTION_STARTED"] },
        "observed_at":  { "type": "string", "format": "date-time" },
        "tags":         { "type": "object", "additionalProperties": { "type": "string" } }
      }
    }
  }
}
```

### Response 200 — `claim-fulfillment-for-processing-response`

```json
{
  "$id": "claim-fulfillment-for-processing-response",
  "type": "object",
  "required": ["fulfillment_id", "attempt_id", "status", "processing_started_at"],
  "properties": {
    "fulfillment_id":        { "type": "string" },
    "attempt_id":            { "type": "string" },
    "status":                { "type": "string", "enum": ["PROCESSING"] },
    "processing_started_at": { "type": "string", "format": "date-time" }
  }
}
```

**Transaction Boundary**

对应 `global_state_machine.md §8 T4`：

* 原子创建 `FulfillmentAttempt(attempt_no = 1, status = EXECUTING)`。
* `Fulfillment.status: PENDING -> PROCESSING`。
* 写入 `current_attempt_id`、`processing_started_at`、`last_diagnostic_trace`。
* append `FULFILLMENT_PROCESSING_STARTED` 审计事件。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `FULFILLMENT_NOT_CLAIMABLE` | 调用开始前 fulfillment 当前状态不是 `PENDING` |
| 409 | `FULFILLMENT_CLAIM_CONFLICT` | CAS claim 竞争失败，且对象已被其他 worker 抢先推进 |
| 409 | `FULFILLMENT_INVARIANT_BROKEN` | 状态、attempt 或 diagnostic trace 组合关系损坏 |

详见 `_shared_error_codes.md`。

---

## Command 2: MarkFulfillmentSucceeded

**Introduced by:** `feat-TKT002-01`
**Purpose:** 由当前持有执行权的 attempt 提交成功终局。

### Request — `mark-fulfillment-succeeded-request`

```json
{
  "$id": "mark-fulfillment-succeeded-request",
  "type": "object",
  "required": [
    "fulfillment_id",
    "attempt_id",
    "expected_version",
    "delivery_result",
    "diagnostic_trace",
    "succeeded_at"
  ],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "attempt_id":       { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "delivery_result":  { "$ref": "#/$defs/delivery_result" },
    "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" },
    "succeeded_at":     { "type": "string", "format": "date-time" }
  },
  "$defs": {
    "delivery_result": {
      "type": "object",
      "required": ["resource_type", "resource_id", "payload_summary", "delivered_at"],
      "properties": {
        "resource_type":   { "type": "string", "minLength": 1 },
        "resource_id":     { "type": "string", "minLength": 1 },
        "payload_summary": { "type": "object", "additionalProperties": true },
        "delivered_at":    { "type": "string", "format": "date-time" }
      }
    },
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":     { "type": "string", "minLength": 1 },
        "decision":     { "type": "string", "enum": ["PROVIDER_CALL_SUCCEEDED", "RESULT_COMMITTED"] },
        "observed_at":  { "type": "string", "format": "date-time" },
        "tags":         { "type": "object", "additionalProperties": { "type": "string" } }
      }
    }
  }
}
```

### Response 200 — `mark-fulfillment-succeeded-response`

```json
{
  "$id": "mark-fulfillment-succeeded-response",
  "type": "object",
  "required": ["fulfillment_id", "attempt_id", "status", "terminal_at"],
  "properties": {
    "fulfillment_id": { "type": "string" },
    "attempt_id":     { "type": "string" },
    "status":         { "type": "string", "enum": ["SUCCEEDED"] },
    "terminal_at":    { "type": "string", "format": "date-time" }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `FULFILLMENT_ATTEMPT_MISMATCH` | `attempt_id` 不是 `current_attempt_id` |
| 409 | `FULFILLMENT_ALREADY_TERMINAL` | fulfillment 已进入终态 |
| 422 | `DELIVERY_RESULT_REQUIRED` | 缺少最小成功结果摘要 |
| 409 | `FULFILLMENT_INVARIANT_BROKEN` | 状态、attempt 或 diagnostic trace 组合关系损坏 |

详见 `_shared_error_codes.md`。

---

## Command 3: MarkFulfillmentFailed

**Introduced by:** `feat-TKT002-01`
**Purpose:** 由当前持有执行权的 attempt 提交失败终局。

### Request — `mark-fulfillment-failed-request`

```json
{
  "$id": "mark-fulfillment-failed-request",
  "type": "object",
  "required": [
    "fulfillment_id",
    "attempt_id",
    "expected_version",
    "failure",
    "diagnostic_trace",
    "failed_at"
  ],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "attempt_id":       { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "failure":          { "$ref": "#/$defs/failure_summary" },
    "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" },
    "failed_at":        { "type": "string", "format": "date-time" }
  },
  "$defs": {
    "failure_summary": {
      "type": "object",
      "required": ["reason_code", "reason_message", "failed_at"],
      "properties": {
        "reason_code": {
          "type": "string",
          "enum": ["PROVIDER_REJECTED", "PROVIDER_TIMEOUT", "PROVIDER_TECHNICAL_FAILURE", "DELIVERY_RESULT_INVALID"]
        },
        "reason_message": { "type": "string", "minLength": 1 },
        "failed_at":      { "type": "string", "format": "date-time" }
      }
    },
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":     { "type": "string", "minLength": 1 },
        "decision":     { "type": "string", "enum": ["PROVIDER_CALL_FAILED", "RESULT_COMMITTED"] },
        "observed_at":  { "type": "string", "format": "date-time" },
        "tags":         { "type": "object", "additionalProperties": { "type": "string" } }
      }
    }
  }
}
```

### Response 200 — `mark-fulfillment-failed-response`

```json
{
  "$id": "mark-fulfillment-failed-response",
  "type": "object",
  "required": ["fulfillment_id", "attempt_id", "status", "terminal_at"],
  "properties": {
    "fulfillment_id": { "type": "string" },
    "attempt_id":     { "type": "string" },
    "status":         { "type": "string", "enum": ["FAILED"] },
    "terminal_at":    { "type": "string", "format": "date-time" }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `FULFILLMENT_ATTEMPT_MISMATCH` | `attempt_id` 不是 `current_attempt_id` |
| 409 | `FULFILLMENT_ALREADY_TERMINAL` | fulfillment 已进入终态 |
| 422 | `FAILURE_SUMMARY_REQUIRED` | 缺少最小失败摘要 |
| 409 | `FULFILLMENT_INVARIANT_BROKEN` | 状态、attempt 或 diagnostic trace 组合关系损坏 |

详见 `_shared_error_codes.md`。

---

## Command 4: RecordFulfillmentResultLeftProcessing

**Introduced by:** `feat-TKT002-01`
**Purpose:** 在外部结果已发生但本地无法安全提交成功或失败时，登记保守滞留诊断。

### Request — `record-fulfillment-result-left-processing-request`

```json
{
  "$id": "record-fulfillment-result-left-processing-request",
  "type": "object",
  "required": [
    "fulfillment_id",
    "attempt_id",
    "expected_version",
    "diagnostic_trace",
    "observed_at"
  ],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "attempt_id":       { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" },
    "observed_at":      { "type": "string", "format": "date-time" }
  },
  "$defs": {
    "diagnostic_trace": {
      "type": "object",
      "required": ["trace_id", "decision", "observed_at", "tags"],
      "properties": {
        "trace_id":     { "type": "string", "minLength": 1 },
        "decision":     { "type": "string", "enum": ["RESULT_LEFT_PROCESSING"] },
        "observed_at":  { "type": "string", "format": "date-time" },
        "tags":         { "type": "object", "additionalProperties": { "type": "string" } }
      }
    }
  }
}
```

### Response 200 — `record-fulfillment-result-left-processing-response`

```json
{
  "$id": "record-fulfillment-result-left-processing-response",
  "type": "object",
  "required": ["fulfillment_id", "attempt_id", "status", "processing_started_at", "last_observed_at"],
  "properties": {
    "fulfillment_id":        { "type": "string" },
    "attempt_id":            { "type": "string" },
    "status":                { "type": "string", "enum": ["PROCESSING"] },
    "processing_started_at": { "type": "string", "format": "date-time" },
    "last_observed_at":      { "type": "string", "format": "date-time" }
  }
}
```

**Transaction Boundary**

对应 `global_state_machine.md §8 T5`：

* `Fulfillment.status` 保持 `PROCESSING`。
* `current_attempt_id` 与 `processing_started_at` 保持不变。
* 更新 `last_diagnostic_trace` 与 attempt 诊断信息。
* append `FULFILLMENT_RESULT_LEFT_PROCESSING` 审计事件。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `FULFILLMENT_ATTEMPT_MISMATCH` | `attempt_id` 不是 `current_attempt_id` |
| 409 | `FULFILLMENT_ALREADY_TERMINAL` | fulfillment 已进入终态 |
| 409 | `FULFILLMENT_INVARIANT_BROKEN` | 状态、attempt 或 diagnostic trace 组合关系损坏 |

详见 `_shared_error_codes.md`。
