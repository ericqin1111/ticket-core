# ADR: feat-TKT002-02-retry-failure-convergence

## 核心边界划分思路

本阶段的核心聚合根定义为 `Fulfillment`。原因不是它承载了所有细节，而是它天然拥有唯一的治理终局与对外状态语义：对象要么继续自动恢复，要么等待人工接管，要么已经成功，要么在人工治理后被最终终止。围绕这一点，`RETRY_PENDING`、`MANUAL_PENDING`、`SUCCEEDED`、`FAILED` 都属于 `Fulfillment` 自身生命周期的一部分，不能下沉为某次尝试的局部结果。

`Fulfillment Attempt` 被建模为从属于 `Fulfillment` 的普通实体，而不是独立聚合根。它负责承载“某一次执行尝试”的过程证据，包括尝试序号、触发来源、分类结果、provider 诊断上下文和执行结论，但它不能单独决定整条履约的最终治理去向。这样可以避免把“attempt 失败”错误等价成“fulfillment 失败”。

失败分类本身被抽象为稳定的平台治理语义，而不是 provider 原始错误的直接镜像。原因是平台需要一个不会随供应商漂移的统一判断面，才能保证自动恢复、人工接管和审计口径一致。因此分类码、分类标签、人工处理价值、是否允许自动恢复等应被视作 `Fulfillment` 治理决策的输入，而 provider 原始错误仅作为诊断上下文保留。

`PROCESSING` 在本阶段仍然是持久治理态，而不是瞬时过渡态。因此“stuck processing”治理必须被视为对 `Fulfillment` 生命周期的正式收敛动作，而不是后台实现细节。文档中采用“是否有充分证据支持安全自动下一步”作为唯一治理判断标准，避免假设平台具备并不存在的外部可观测性；只有 `confirmedNotSucceeded=true` 且 `duplicateExecutionRiskControllable=true` 时才允许自动回到重试轨道，并且必须留下稳定的治理原因码 `PROCESSING_STUCK_SAFE_TO_RETRY` 作为该分支的契约化落点。

attempt 的 `trigger` 需要区分“实体历史上可能出现的触发来源全集”和“某个命令接口允许接收的触发来源子集”。因此 `StartRetryAttempt` 不应直接暴露完整 `AttemptTrigger`，而应收窄为仅接受 `FAST_RETRY`、`BACKOFF_RETRY` 两类自动重试触发。这样才能保持“先分类、后重试”的治理顺序，并让 `RETRY_PENDING -> PROCESSING` 的入口与状态机中的 retry 分支保持 1:1 映射，避免调用方绕过分类语义，把首次执行或超时治理伪装成 retry 启动。

## 关键技术权衡

并发控制上，优先保证单条 `Fulfillment` 的唯一治理推进，而不是提高吞吐层面的自动恢复频率。原因是票务履约的错误代价集中在重复执行与重复终局，因此设计上要求所有状态跃迁和重试预算消耗都围绕同一个聚合根串行收敛。这里倾向采用聚合级乐观并发语义，而不是让多个调度线程自由争抢后再靠补偿收敛。

一致性上，Stage B 仅处理单条履约聚合内的治理闭环，不引入跨聚合的强一致分布式事务。失败分类、预算判断、状态迁移和审计记录应被视作同一治理决策的一部分；若后续需要跨模块通知人工工作台或下游观察者，更适合通过稳定的领域事件做最终一致传播，而不是把外部协同耦合进本阶段主路径。

幂等策略上，选择“保守拒绝重复推进”而不是“重复请求尽量成功”。原因是 PM 已明确正确性优先于自动成功率，尤其 `Uncertain Result` 场景下不能为了追求恢复率而冒重复出票、重复发码风险。因此所有自动调度、超时回放和重复提交，都必须以同一 `Fulfillment` 当前状态、重试预算与最近一次 attempt 结果为幂等判定基础。

重试策略上，采用“分类优先 + 1 次快速重试 + 极小额度退避重试预算 + 耗尽后转人工”的最小恢复模型。这一方案刻意放弃高自动恢复率，换取边界清晰、状态稳定和诊断可解释。尤其在 stuck `PROCESSING` 场景中，若已有充分证据证明“尚未成功且重复执行风险可控”，则必须产出 `FailureDecision(category='RETRYABLE_TECHNICAL_FAILURE', reasonCode='PROCESSING_STUCK_SAFE_TO_RETRY')` 后再回到 `RETRY_PENDING`；若仍属 `Uncertain Result` 或无充分证据，则直接转 `MANUAL_PENDING`，并产出 `FailureDecision(category='UNCERTAIN_RESULT', reasonCode='PROCESSING_STUCK_UNSAFE_TO_RETRY' | 'EXTERNAL_RESULT_UNKNOWN')`。这里“无充分证据”不是异常分支，而是标准治理出口。

超时治理的一致性重点不只在 `Fulfillment` 的去向，也在 attempt 子实体的同步收口。既然 blueprint 引入了 `ABANDONED` 作为 “该次执行已被治理接管、不得继续作为活跃分支推进” 的语义节点，那么 `GovernProcessingTimeout` 就必须把当前活跃 `FulfillmentAttempt` 从 `STARTED` 明确改写为 `ABANDONED`，并把该 attempt 纳入返回体和审计记录。否则会出现 fulfillment 已进入 `RETRY_PENDING` 或 `MANUAL_PENDING`、而原 attempt 仍停在 `STARTED` 的契约裂缝，破坏单聚合内状态闭环。

最终失败语义上，坚持 `FAILED` 只表示人工治理后的终局，而不再复用为自动流程失败出口。这一拆分增加了一个治理态，但显著降低了语义混淆：自动流程是否继续、是否等待人工、是否已经最终终止，分别由不同状态承担，后续审计、运营口径和 Stage C 接续都会更稳定。
