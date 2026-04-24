# 3_pr_summary — feat-TKT002-02-retry-failure-convergence

## [本轮修复范围]

- 服务实现：`src/main/java/com/ticket/core/fulfillment/service/FulfillmentGovernanceService.java`
- 单测：`src/test/java/com/ticket/core/fulfillment/service/FulfillmentGovernanceServiceTest.java`

## [Reviewer 反馈修复结果]

- 已把 `ScheduleRetryAfterFailure` 的预算耗尽分支改为 Blueprint 要求的 `RETRY_PENDING -> MANUAL_PENDING` 收敛。
  - 总预算已耗尽时，直接转入 `MANUAL_PENDING` 并输出 `MOVED_TO_MANUAL_PENDING` 审计：`FulfillmentGovernanceService.java:149-151, 420-448`，测试 `FulfillmentGovernanceServiceTest.java:73-108`
  - 退避次数已耗尽时，同样收敛到 `MANUAL_PENDING`，不再停留在异常返回：`FulfillmentGovernanceService.java:174-175, 420-448`，测试 `FulfillmentGovernanceServiceTest.java:110-148`

- 已收敛 `GetFulfillmentGovernanceView` 的对外异常面，查询接口现在只保留 Blueprint 已签约的 `FULFILLMENT_NOT_FOUND`。
  - 查询路径改为对聚合 JSON 使用宽松解析，`retryState` 退化为默认值、可选视图字段退化为 `null`，避免把内部脏数据扩成 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:400-417, 724-840, 865-887`
  - 单测已覆盖损坏 `retryStateJson` / 审计快照 JSON 时仍可返回查询结果：`FulfillmentGovernanceServiceTest.java:436-463`

- 已补齐 `ClassifyAttemptFailure` 的契约对账证据。
  - 成功主路径：`FulfillmentGovernanceService.java:55-123`，测试 `FulfillmentGovernanceServiceTest.java:30-70`
  - `FULFILLMENT_NOT_FOUND`：`FulfillmentGovernanceService.java:62, 436-440`，测试 `FulfillmentGovernanceServiceTest.java:404-428`
  - `FAILURE_DECISION_REQUIRED`：`FulfillmentGovernanceService.java:493-495, 535`，测试 `FulfillmentGovernanceServiceTest.java:393-405`
  - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:58-60, 425-429`，测试 `FulfillmentGovernanceServiceTest.java:408-424`
  - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:451-455`，测试 `FulfillmentGovernanceServiceTest.java:426-450`
  - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:443-449, 463-468`，测试 `FulfillmentGovernanceServiceTest.java:452-479`
  - `ATTEMPT_ALREADY_FINALIZED`：`FulfillmentGovernanceService.java:470-474`，测试 `FulfillmentGovernanceServiceTest.java:481-509`

- 已把 `GovernProcessingTimeout` 的 Blueprint 关键闭环证据补齐到实现、测试与摘要。
  - 已将 attempt 非 `STARTED` 的拒绝分支收敛为 Blueprint 已签约的 `INVALID_STATUS_TRANSITION`，不再对外暴露 `ATTEMPT_ALREADY_FINALIZED`：`FulfillmentGovernanceService.java:305-306, 477-480`，测试 `FulfillmentGovernanceServiceTest.java:760-784`
  - 活跃 attempt 从 `STARTED` 改写为 `ABANDONED`：`FulfillmentGovernanceService.java:304-315`
  - 同次治理输出 `ATTEMPT_ABANDONED` 与 `PROCESSING_TIMEOUT_GOVERNED` 审计：`FulfillmentGovernanceService.java:334-342`
  - `PROCESSING_NOT_TIMED_OUT` 分支：`FulfillmentGovernanceService.java:299-303`，测试 `FulfillmentGovernanceServiceTest.java:695-717`
  - 安全可重试分支：`FulfillmentGovernanceService.java:539-555`，测试 `FulfillmentGovernanceServiceTest.java:229-267`
  - 结果不确定分支：
    - `EXTERNAL_RESULT_UNKNOWN`：`FulfillmentGovernanceService.java:556-565`，测试 `FulfillmentGovernanceServiceTest.java:189-227`
    - `PROCESSING_STUCK_UNSAFE_TO_RETRY`：`FulfillmentGovernanceService.java:556-565`，测试 `FulfillmentGovernanceServiceTest.java:720-757`

- 已补齐所有超出 Blueprint 的微观防御/优化的 `Worker Decision` 留痕。
  - 退避档位与 `backoffSchedule` 对齐校验，脏配置统一映射 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:180-183`
  - `ATTEMPT_STARTED` 审计允许读取可空 `latestFailure`：`FulfillmentGovernanceService.java:273-277`
  - `retryPolicy` 缺失/脏数据统一映射 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:613-619`
  - `retryState` 缺失/脏数据统一映射 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:622-629`
  - JSON 反序列化失败统一映射 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:741-750`
  - `MOVED_TO_SUCCEEDED` 审计允许空 `latestFailure`：`FulfillmentGovernanceService.java:385-389`
  - `currentProcessingLease` 仅在关键时间戳存在时才对外返回：`FulfillmentGovernanceService.java:673-679`

## [当前契约对账]

- `ClassifyAttemptFailure`
  - 实现：`FulfillmentGovernanceService.java:55-123`
  - 主路径测试：`FulfillmentGovernanceServiceTest.java:30-70`
  - 异常分支：
    - `FULFILLMENT_NOT_FOUND`：`FulfillmentGovernanceService.java:62, 436-440`；`FulfillmentGovernanceServiceTest.java:404-428`
    - `FAILURE_DECISION_REQUIRED`：`FulfillmentGovernanceService.java:493-495, 535`；`FulfillmentGovernanceServiceTest.java:393-405`
    - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:443-449, 463-468`；`FulfillmentGovernanceServiceTest.java:452-479`
    - `ATTEMPT_ALREADY_FINALIZED`：`FulfillmentGovernanceService.java:470-474`；`FulfillmentGovernanceServiceTest.java:481-509`
    - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:58-60, 425-429`；`FulfillmentGovernanceServiceTest.java:408-424`
    - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:451-455`；`FulfillmentGovernanceServiceTest.java:426-450`

- `ScheduleRetryAfterFailure`
  - 实现：`FulfillmentGovernanceService.java:127-210, 420-448`
  - 已覆盖成功/异常：
    - 预算耗尽收敛到 `MANUAL_PENDING`：`FulfillmentGovernanceService.java:149-151, 174-175, 420-448`；`FulfillmentGovernanceServiceTest.java:73-148`
    - `FAILURE_CATEGORY_NOT_RETRYABLE`：`FulfillmentGovernanceService.java:137-140`；`FulfillmentGovernanceServiceTest.java:511-542`
    - `FAST_RETRY_ALREADY_USED`：`FulfillmentGovernanceService.java:152-156`；`FulfillmentGovernanceServiceTest.java:544-573`
    - `NEXT_RETRY_NOT_DUE`：`FulfillmentGovernanceService.java:177-178`；`FulfillmentGovernanceServiceTest.java:575-604`
    - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:171-183, 199-200`；`FulfillmentGovernanceServiceTest.java:606-635`
    - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:130-132, 425-429`
    - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:135, 451-455`

- `StartRetryAttempt`
  - 实现：`FulfillmentGovernanceService.java:214-285`
  - 主路径测试：`FulfillmentGovernanceServiceTest.java:99-142`
  - 额外成功路径证明 `ATTEMPT_STARTED` 审计允许空 `latestFailure`：`FulfillmentGovernanceService.java:273-277`；`FulfillmentGovernanceServiceTest.java:144-187`
  - 异常分支：
    - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:223-226`
    - `NEXT_RETRY_NOT_DUE`：`FulfillmentGovernanceService.java:229-234`；`FulfillmentGovernanceServiceTest.java:637-666`
    - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:217-219, 425-429`
    - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:222, 451-455`

- `GovernProcessingTimeout`
  - 实现：`FulfillmentGovernanceService.java:288-351`
  - 活跃 attempt 改写为 `ABANDONED`：`FulfillmentGovernanceService.java:304-315`
  - 审计闭环 `ATTEMPT_ABANDONED` + `PROCESSING_TIMEOUT_GOVERNED`：`FulfillmentGovernanceService.java:334-342`
  - 测试：
    - 结果不确定 `EXTERNAL_RESULT_UNKNOWN`：`FulfillmentGovernanceServiceTest.java:189-227`
    - 安全可重试：`FulfillmentGovernanceServiceTest.java:229-267`
    - `PROCESSING_NOT_TIMED_OUT`：`FulfillmentGovernanceServiceTest.java:695-717`
    - 结果不确定 `PROCESSING_STUCK_UNSAFE_TO_RETRY`：`FulfillmentGovernanceServiceTest.java:720-757`
  - 异常分支：
    - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:298, 305-306, 444-449, 458-460, 477-480, 489-491`；`FulfillmentGovernanceServiceTest.java:760-784`
    - `PROCESSING_NOT_TIMED_OUT`：`FulfillmentGovernanceService.java:299-303`；`FulfillmentGovernanceServiceTest.java:695-717`
    - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:291-293, 425-429`
    - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:296, 451-455`

- `RecordAttemptSuccess`
  - 实现：`FulfillmentGovernanceService.java:354-397`
  - 主路径测试：`FulfillmentGovernanceServiceTest.java:301-335`
  - 异常分支：
    - `ATTEMPT_ALREADY_FINALIZED`：`FulfillmentGovernanceService.java:366-367, 470-474`；`FulfillmentGovernanceServiceTest.java:269-299`
    - `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:364-366, 443-449, 463-468`；`FulfillmentGovernanceServiceTest.java:733-758`
    - `IDEMPOTENT_REPLAY_CONFLICT`：`FulfillmentGovernanceService.java:357-359, 425-429`
    - `CONCURRENCY_VERSION_MISMATCH`：`FulfillmentGovernanceService.java:362, 451-455`

- `GetFulfillmentGovernanceView`
  - 实现：`FulfillmentGovernanceService.java:400-417, 724-840, 865-887`
  - 主路径测试：`FulfillmentGovernanceServiceTest.java:398-434`
  - 异常/边界：
    - `FULFILLMENT_NOT_FOUND`：`FulfillmentGovernanceService.java:467-472`；`FulfillmentGovernanceServiceTest.java:909-922`
    - 损坏 `retryStateJson` / 审计快照 JSON 时降级为默认值或 `null`，不再暴露 `INVALID_STATUS_TRANSITION`：`FulfillmentGovernanceService.java:400-417, 724-840, 865-887`；`FulfillmentGovernanceServiceTest.java:436-463`
    - 空 lease 视图不外露壳对象：`FulfillmentGovernanceService.java:744-749`；`FulfillmentGovernanceServiceTest.java:926-943`

## [验证]

- 已执行：`mvn -q -Dmaven.repo.local=/tmp/m2 -Dtest=FulfillmentGovernanceServiceTest test`
