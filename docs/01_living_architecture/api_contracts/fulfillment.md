# API Contract — Fulfillment

**Maintained by:** librarian
**Last updated by:** migration (from feat-TKT002-01 / feat-TKT002-02 blueprint / implementation)
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
        "status":                { "type": "string", "enum": ["PENDING", "PROCESSING", "RETRY_PENDING", "MANUAL_PENDING", "SUCCEEDED", "FAILED"] },
        "current_attempt_id":    { "type": "string" },
        "latest_attempt_id":     { "type": "string" },
        "processing_started_at": { "type": "string", "format": "date-time" },
        "processing_timeout_at": { "type": "string", "format": "date-time" },
        "terminal_at":           { "type": "string", "format": "date-time" },
        "execution_path":        { "type": "string", "enum": ["DEFAULT_PROVIDER"] },
        "delivery_result":       { "$ref": "#/$defs/delivery_result" },
        "last_failure":          { "$ref": "#/$defs/failure_summary" },
        "latest_failure":        { "$ref": "#/$defs/failure_decision" },
        "retry_policy":          { "$ref": "#/$defs/retry_policy_snapshot" },
        "retry_state":           { "$ref": "#/$defs/retry_state" },
        "current_processing_lease": { "$ref": "#/$defs/processing_lease" },
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
        "trigger":          { "type": "string", "enum": ["INITIAL_EXECUTION", "FAST_RETRY", "BACKOFF_RETRY", "PROCESSING_TIMEOUT_GOVERNANCE"] },
        "execution_status": { "type": "string", "enum": ["STARTED", "SUCCEEDED", "FAILED_CLASSIFIED", "ABANDONED"] },
        "status":           { "type": "string", "enum": ["EXECUTING", "SUCCEEDED", "FAILED", "ABANDONED"] },
        "dispatcher_run_id": { "type": "string" },
        "executor_ref":     { "type": "string" },
        "execution_path":   { "type": "string", "enum": ["DEFAULT_PROVIDER"] },
        "claimed_at":       { "type": "string", "format": "date-time" },
        "started_at":       { "type": "string", "format": "date-time" },
        "finished_at":      { "type": "string", "format": "date-time" },
        "delivery_result":  { "$ref": "#/$defs/delivery_result" },
        "failure":          { "$ref": "#/$defs/failure_summary" },
        "failure_decision": { "$ref": "#/$defs/failure_decision" },
        "provider_diagnostic": { "$ref": "#/$defs/provider_diagnostic" },
        "diagnostic_trace": { "$ref": "#/$defs/diagnostic_trace" }
      }
    },
    "retry_policy_snapshot": {
      "type": "object",
      "required": ["fast_retry_limit", "backoff_retry_limit", "total_retry_budget", "backoff_schedule"],
      "properties": {
        "fast_retry_limit":    { "type": "integer", "minimum": 0 },
        "backoff_retry_limit": { "type": "integer", "minimum": 0 },
        "total_retry_budget":  { "type": "integer", "minimum": 0 },
        "backoff_schedule": {
          "type": "array",
          "items": { "type": "string", "minLength": 1 }
        }
      }
    },
    "retry_state": {
      "type": "object",
      "required": ["fast_retry_used", "backoff_retry_used", "total_retry_used", "budget_exhausted"],
      "properties": {
        "fast_retry_used":    { "type": "integer", "minimum": 0 },
        "backoff_retry_used": { "type": "integer", "minimum": 0 },
        "total_retry_used":   { "type": "integer", "minimum": 0 },
        "next_retry_at":      { "type": "string", "format": "date-time" },
        "budget_exhausted":   { "type": "boolean" }
      }
    },
    "processing_lease": {
      "type": "object",
      "required": ["processing_started_at", "timeout_at"],
      "properties": {
        "processing_started_at": { "type": "string", "format": "date-time" },
        "timeout_at":            { "type": "string", "format": "date-time" }
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
    "failure_decision": {
      "type": "object",
      "required": [
        "category",
        "reason_code",
        "retry_disposition",
        "manual_review_required",
        "final_termination_suggested",
        "rationale",
        "classified_at"
      ],
      "properties": {
        "category": {
          "type": "string",
          "enum": [
            "RETRYABLE_TECHNICAL_FAILURE",
            "FINAL_BUSINESS_REJECTED",
            "MANUAL_REVIEW_REQUIRED",
            "UNCERTAIN_RESULT"
          ]
        },
        "reason_code": {
          "type": "string",
          "enum": [
            "NETWORK_TIMEOUT",
            "GATEWAY_TEMPORARY_ERROR",
            "PROVIDER_RATE_LIMITED",
            "PROVIDER_TEMPORARILY_UNAVAILABLE",
            "FULFILLMENT_WINDOW_EXPIRED",
            "ORDER_CONDITION_INVALID",
            "PROVIDER_PERMANENT_REJECTED",
            "UPSTREAM_DATA_REQUIRES_REVIEW",
            "MANUAL_SOURCE_SWITCH_REQUIRED",
            "EXTERNAL_RESULT_UNKNOWN",
            "PROCESSING_STUCK_SAFE_TO_RETRY",
            "PROCESSING_STUCK_UNSAFE_TO_RETRY"
          ]
        },
        "retry_disposition": {
          "type": "string",
          "enum": ["ALLOW_FAST_RETRY", "ALLOW_BACKOFF_RETRY", "STOP_AND_MANUAL", "STOP_AND_FINAL_FAIL"]
        },
        "manual_review_required":      { "type": "boolean" },
        "final_termination_suggested": { "type": "boolean" },
        "rationale":                   { "type": "string", "minLength": 1 },
        "classified_at":               { "type": "string", "format": "date-time" }
      }
    },
    "provider_diagnostic": {
      "type": "object",
      "required": ["raw_outcome_known"],
      "properties": {
        "provider_code":     { "type": "string" },
        "provider_message":  { "type": "string" },
        "raw_outcome_known": { "type": "boolean" }
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

---

## Query 4: GetFulfillmentGovernanceView

**Introduced by:** `feat-TKT002-02`
**Purpose:** 返回履约治理视图，聚合当前治理状态、最近一次 attempt、最近失败决策、重试预算与最近治理审计。
**Idempotency:** 幂等查询，无 `Idempotency-Key` 要求。

### Request — `get-fulfillment-governance-view-query`

```json
{
  "$id": "get-fulfillment-governance-view-query",
  "type": "object",
  "required": ["fulfillment_id"],
  "properties": {
    "fulfillment_id": { "type": "string", "minLength": 1 }
  }
}
```

### Response 200 — `get-fulfillment-governance-view-result`

```json
{
  "$id": "get-fulfillment-governance-view-result",
  "type": "object",
  "required": ["fulfillment", "retry_state", "recent_audit_records"],
  "properties": {
    "fulfillment": { "$ref": "#/$defs/fulfillment" },
    "latest_attempt": { "$ref": "#/$defs/fulfillment_attempt" },
    "latest_failure": { "$ref": "#/$defs/failure_decision" },
    "retry_state": { "$ref": "#/$defs/retry_state" },
    "recent_audit_records": {
      "type": "array",
      "items": { "$ref": "#/$defs/governance_audit_record" }
    }
  },
  "$defs": {
    "governance_audit_record": {
      "type": "object",
      "required": ["audit_id", "fulfillment_id", "action_type", "actor_type", "occurred_at"],
      "properties": {
        "audit_id":        { "type": "string" },
        "fulfillment_id":  { "type": "string" },
        "attempt_id":      { "type": "string" },
        "action_type": {
          "type": "string",
          "enum": [
            "ATTEMPT_STARTED",
            "ATTEMPT_ABANDONED",
            "ATTEMPT_CLASSIFIED",
            "FAST_RETRY_SCHEDULED",
            "BACKOFF_RETRY_SCHEDULED",
            "MOVED_TO_RETRY_PENDING",
            "MOVED_TO_MANUAL_PENDING",
            "MOVED_TO_FAILED",
            "MOVED_TO_SUCCEEDED",
            "PROCESSING_TIMEOUT_GOVERNED"
          ]
        },
        "from_status":      { "type": "string" },
        "to_status":        { "type": "string" },
        "failure_category": { "type": "string" },
        "reason_code":      { "type": "string" },
        "retry_budget_snapshot": { "$ref": "#/$defs/retry_state" },
        "actor_type":       { "type": "string", "enum": ["SYSTEM"] },
        "occurred_at":      { "type": "string", "format": "date-time" }
      }
    }
  }
}
```

**Field notes**

* 查询视图中的 `retry_state` 在底层 JSON 缺失或损坏时按默认值降级，不扩大为额外业务错误码。
* `latest_attempt`、`latest_failure` 与 `recent_audit_records[*].retry_budget_snapshot` 允许为空，以兼容历史数据和部分治理前快照。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |

详见 `_shared_error_codes.md`。

---

## Command 5: ClassifyAttemptFailure

**Introduced by:** `feat-TKT002-02`
**Purpose:** 对 `PROCESSING` 中的当前 attempt 失败进行稳定分类，并把聚合收敛到 `RETRY_PENDING`、`MANUAL_PENDING` 或 `FAILED`。

### Request — `classify-attempt-failure-command`

```json
{
  "$id": "classify-attempt-failure-command",
  "type": "object",
  "required": ["fulfillment_id", "attempt_id", "idempotency_key", "expected_version", "observed_failure"],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "attempt_id":       { "type": "string", "minLength": 1 },
    "idempotency_key":  { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "observed_failure": {
      "type": "object",
      "required": ["raw_outcome_known", "failure_signal"],
      "properties": {
        "provider_code":     { "type": "string" },
        "provider_message":  { "type": "string" },
        "raw_outcome_known": { "type": "boolean" },
        "failure_signal":    { "type": "string", "minLength": 1 }
      }
    }
  }
}
```

### Response 200 — `classify-attempt-failure-result`

```json
{
  "$id": "classify-attempt-failure-result",
  "type": "object",
  "required": ["fulfillment", "attempt", "decision", "emitted_audit_records"],
  "properties": {
    "fulfillment":          { "$ref": "#/$defs/fulfillment" },
    "attempt":              { "$ref": "#/$defs/fulfillment_attempt" },
    "decision":             { "$ref": "#/$defs/failure_decision" },
    "emitted_audit_records": {
      "type": "array",
      "items": { "$ref": "#/$defs/governance_audit_record" }
    }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 422 | `FAILURE_DECISION_REQUIRED` | 观测信息不足以导出唯一失败分类 |
| 409 | `INVALID_STATUS_TRANSITION` | fulfillment 非 `PROCESSING`，或 `attempt_id` 非当前活跃 attempt |
| 409 | `ATTEMPT_ALREADY_FINALIZED` | 当前 attempt 已不处于 `STARTED` |
| 409 | `IDEMPOTENT_REPLAY_CONFLICT` | 相同治理动作的同 key 请求在处理中或回放冲突 |
| 409 | `CONCURRENCY_VERSION_MISMATCH` | `expected_version` 与当前聚合版本不一致 |

详见 `_shared_error_codes.md`。

---

## Command 6: ScheduleRetryAfterFailure

**Introduced by:** `feat-TKT002-02`
**Purpose:** 在 `RETRY_PENDING` 下消耗重试预算并安排快速重试或退避重试；预算耗尽时收敛到 `MANUAL_PENDING`。

### Request — `schedule-retry-after-failure-command`

```json
{
  "$id": "schedule-retry-after-failure-command",
  "type": "object",
  "required": ["fulfillment_id", "idempotency_key", "expected_version", "requested_mode", "now"],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "idempotency_key":  { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "requested_mode":   { "type": "string", "enum": ["FAST_RETRY", "BACKOFF_RETRY"] },
    "now":              { "type": "string", "format": "date-time" }
  }
}
```

### Response 200 — `schedule-retry-after-failure-result`

```json
{
  "$id": "schedule-retry-after-failure-result",
  "type": "object",
  "required": ["fulfillment", "emitted_audit_records"],
  "properties": {
    "fulfillment":               { "$ref": "#/$defs/fulfillment" },
    "scheduled_attempt_trigger": { "type": "string", "enum": ["FAST_RETRY", "BACKOFF_RETRY"] },
    "next_retry_at":             { "type": "string", "format": "date-time" },
    "emitted_audit_records": {
      "type": "array",
      "items": { "$ref": "#/$defs/governance_audit_record" }
    }
  }
}
```

**Field notes**

* 当预算已经耗尽时，当前实现返回成功并把 fulfillment 收敛到 `MANUAL_PENDING`，而不是对外抛出错误。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `FAILURE_CATEGORY_NOT_RETRYABLE` | 最近一次失败分类不允许自动重试 |
| 409 | `FAST_RETRY_ALREADY_USED` | 请求快速重试，但快速重试额度已用完 |
| 409 | `NEXT_RETRY_NOT_DUE` | 请求退避重试，但 `next_retry_at` 尚未到达 |
| 409 | `INVALID_STATUS_TRANSITION` | fulfillment 不在 `RETRY_PENDING`，或请求模式与当前策略不匹配 |
| 409 | `IDEMPOTENT_REPLAY_CONFLICT` | 相同治理动作的同 key 请求在处理中或回放冲突 |
| 409 | `CONCURRENCY_VERSION_MISMATCH` | `expected_version` 与当前聚合版本不一致 |

详见 `_shared_error_codes.md`。

---

## Command 7: StartRetryAttempt

**Introduced by:** `feat-TKT002-02`
**Purpose:** 从 `RETRY_PENDING` 创建新的 retry attempt，并重新进入 `PROCESSING`。

### Request — `start-retry-attempt-command`

```json
{
  "$id": "start-retry-attempt-command",
  "type": "object",
  "required": ["fulfillment_id", "trigger", "idempotency_key", "expected_version", "now"],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "trigger":          { "type": "string", "enum": ["FAST_RETRY", "BACKOFF_RETRY"] },
    "idempotency_key":  { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "now":              { "type": "string", "format": "date-time" }
  }
}
```

### Response 200 — `start-retry-attempt-result`

```json
{
  "$id": "start-retry-attempt-result",
  "type": "object",
  "required": ["fulfillment", "attempt", "emitted_audit_records"],
  "properties": {
    "fulfillment":          { "$ref": "#/$defs/fulfillment" },
    "attempt":              { "$ref": "#/$defs/fulfillment_attempt" },
    "emitted_audit_records": {
      "type": "array",
      "items": { "$ref": "#/$defs/governance_audit_record" }
    }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `INVALID_STATUS_TRANSITION` | fulfillment 不在 `RETRY_PENDING`，或 `trigger` 非允许的 retry trigger |
| 409 | `NEXT_RETRY_NOT_DUE` | `BACKOFF_RETRY` 的 `next_retry_at` 尚未到达 |
| 409 | `IDEMPOTENT_REPLAY_CONFLICT` | 相同治理动作的同 key 请求在处理中或回放冲突 |
| 409 | `CONCURRENCY_VERSION_MISMATCH` | `expected_version` 与当前聚合版本不一致 |

详见 `_shared_error_codes.md`。

---

## Command 8: GovernProcessingTimeout

**Introduced by:** `feat-TKT002-02`
**Purpose:** 对超时的 `PROCESSING` 履约执行治理接管，把当前 attempt 收口为 `ABANDONED`，并根据证据决定回到 `RETRY_PENDING` 还是进入 `MANUAL_PENDING`。

### Request — `govern-processing-timeout-command`

```json
{
  "$id": "govern-processing-timeout-command",
  "type": "object",
  "required": ["fulfillment_id", "idempotency_key", "expected_version", "now", "safe_to_retry_evidence"],
  "properties": {
    "fulfillment_id":   { "type": "string", "minLength": 1 },
    "idempotency_key":  { "type": "string", "minLength": 1 },
    "expected_version": { "type": "integer" },
    "now":              { "type": "string", "format": "date-time" },
    "safe_to_retry_evidence": {
      "type": "object",
      "properties": {
        "confirmed_not_succeeded": { "type": "boolean" },
        "duplicate_execution_risk_controllable": { "type": "boolean" }
      }
    }
  }
}
```

### Response 200 — `govern-processing-timeout-result`

```json
{
  "$id": "govern-processing-timeout-result",
  "type": "object",
  "required": ["fulfillment", "attempt", "decision", "emitted_audit_records"],
  "properties": {
    "fulfillment":          { "$ref": "#/$defs/fulfillment" },
    "attempt":              { "$ref": "#/$defs/fulfillment_attempt" },
    "decision":             { "$ref": "#/$defs/failure_decision" },
    "emitted_audit_records": {
      "type": "array",
      "items": { "$ref": "#/$defs/governance_audit_record" }
    }
  }
}
```

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `INVALID_STATUS_TRANSITION` | fulfillment 非 `PROCESSING`，或当前 attempt 已不再是可治理的活跃 attempt |
| 409 | `PROCESSING_NOT_TIMED_OUT` | processing lease 尚未超时 |
| 409 | `IDEMPOTENT_REPLAY_CONFLICT` | 相同治理动作的同 key 请求在处理中或回放冲突 |
| 409 | `CONCURRENCY_VERSION_MISMATCH` | `expected_version` 与当前聚合版本不一致 |

详见 `_shared_error_codes.md`。

---

## Command 9: RecordAttemptSuccess

**Introduced by:** `feat-TKT002-02`
**Purpose:** 使用治理语义收口当前活跃 attempt 的成功终局，并输出治理审计。

**Compatibility note**

* 该命令与 `feat-TKT002-01` 的 `MarkFulfillmentSucceeded` 语义重叠，但当前实现已补充 Stage B 所需的治理字段与审计口径。

### Error Responses

| HTTP | `code` | 触发条件 |
|:--|:--|:--|
| 404 | `FULFILLMENT_NOT_FOUND` | `fulfillment_id` 不存在 |
| 409 | `ATTEMPT_ALREADY_FINALIZED` | 当前 attempt 已不处于 `STARTED` |
| 409 | `INVALID_STATUS_TRANSITION` | fulfillment 非 `PROCESSING`，或 `attempt_id` 非当前活跃 attempt |
| 409 | `IDEMPOTENT_REPLAY_CONFLICT` | 相同治理动作的同 key 请求在处理中或回放冲突 |
| 409 | `CONCURRENCY_VERSION_MISMATCH` | `expected_version` 与当前聚合版本不一致 |

详见 `_shared_error_codes.md`。
