package com.ticket.core.fulfillment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.fulfillment.dto.*;
import com.ticket.core.fulfillment.entity.FulfillmentAttemptRecord;
import com.ticket.core.fulfillment.entity.FulfillmentGovernanceAuditRecordEntity;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentAttemptRecordMapper;
import com.ticket.core.fulfillment.mapper.FulfillmentGovernanceAuditRecordMapper;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class FulfillmentGovernanceService {

    private static final Duration DEFAULT_PROCESSING_LEASE = Duration.ofMinutes(15);
    private static final int RECENT_AUDIT_LIMIT = 20;

    private final FulfillmentRecordMapper fulfillmentRecordMapper;
    private final FulfillmentAttemptRecordMapper fulfillmentAttemptRecordMapper;
    private final FulfillmentGovernanceAuditRecordMapper governanceAuditRecordMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public FulfillmentGovernanceService(FulfillmentRecordMapper fulfillmentRecordMapper,
                                        FulfillmentAttemptRecordMapper fulfillmentAttemptRecordMapper,
                                        FulfillmentGovernanceAuditRecordMapper governanceAuditRecordMapper,
                                        IdempotencyService idempotencyService,
                                        ObjectMapper objectMapper) {
        this.fulfillmentRecordMapper = fulfillmentRecordMapper;
        this.fulfillmentAttemptRecordMapper = fulfillmentAttemptRecordMapper;
        this.governanceAuditRecordMapper = governanceAuditRecordMapper;
        this.idempotencyService = idempotencyService;
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper.copy()).findAndRegisterModules();
    }

    @Transactional
    public ClassifyAttemptFailureResult classifyAttemptFailure(ClassifyAttemptFailureCommand command) {
        IdempotencyRecord idempotencyRecord = beginIdempotentAction(
                "CLASSIFY_ATTEMPT_FAILURE", command.getFulfillmentId(), command.getIdempotencyKey(), command);
        if (!idempotencyRecord.isNewlyCreated()) {
            return replayOrReject(idempotencyRecord, ClassifyAttemptFailureResult.class);
        }

        FulfillmentRecord fulfillment = requireFulfillment(command.getFulfillmentId());
        ensureVersionMatches(fulfillment, command.getExpectedVersion());
        ensureFulfillmentStatus(fulfillment, "PROCESSING");
        FulfillmentAttemptRecord attempt = requireAttempt(command.getAttemptId(), command.getFulfillmentId());
        ensureAttemptActive(fulfillment, attempt, command.getAttemptId());
        ensureAttemptStarted(attempt);

        RetryState retryState = requireRetryState(fulfillment);
        RetryPolicySnapshot retryPolicy = requireRetryPolicy(fulfillment);
        OffsetDateTime classifiedAt = OffsetDateTime.now(ZoneOffset.UTC);
        FailureDecision decision = classifyObservedFailure(command.getObservedFailure(), retryState, retryPolicy, classifiedAt);
        ProviderDiagnostic providerDiagnostic = ProviderDiagnostic.builder()
                .providerCode(command.getObservedFailure().getProviderCode())
                .providerMessage(command.getObservedFailure().getProviderMessage())
                .rawOutcomeKnown(Boolean.TRUE.equals(command.getObservedFailure().getRawOutcomeKnown()))
                .build();

        attempt.setExecutionStatus("FAILED_CLASSIFIED");
        attempt.setStatus("FAILED");
        attempt.setFailureDecisionJson(toJson(decision));
        attempt.setProviderDiagnosticJson(toJson(providerDiagnostic));
        attempt.setFinishedAt(toLocalDateTime(classifiedAt));
        updateAttempt(attempt);

        String fromStatus = fulfillment.getStatus();
        if ("RETRYABLE_TECHNICAL_FAILURE".equals(decision.getCategory())) {
            fulfillment.setStatus("RETRY_PENDING");
        } else if ("FINAL_BUSINESS_REJECTED".equals(decision.getCategory())) {
            fulfillment.setStatus("FAILED");
            fulfillment.setTerminalAt(toLocalDateTime(classifiedAt));
        } else {
            fulfillment.setStatus("MANUAL_PENDING");
        }
        fulfillment.setCurrentAttemptId(null);
        fulfillment.setLatestAttemptId(attempt.getAttemptId());
        fulfillment.setProcessingStartedAt(null);
        fulfillment.setProcessingTimeoutAt(null);
        fulfillment.setLatestFailureDecisionJson(toJson(decision));
        RetryState nextRetryState = RetryState.builder()
                .fastRetryUsed(retryState.getFastRetryUsed())
                .backoffRetryUsed(retryState.getBackoffRetryUsed())
                .totalRetryUsed(retryState.getTotalRetryUsed())
                .nextRetryAt(null)
                .budgetExhausted(Boolean.TRUE.equals(retryState.getBudgetExhausted()))
                .build();
        fulfillment.setRetryStateJson(toJson(nextRetryState));
        updateFulfillment(fulfillment);

        List<GovernanceAuditRecord> emittedAuditRecords = new ArrayList<>();
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), "ATTEMPT_CLASSIFIED",
                fromStatus, fulfillment.getStatus(), decision, nextRetryState, classifiedAt));
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), transitionAction(fulfillment.getStatus()),
                fromStatus, fulfillment.getStatus(), decision, nextRetryState, classifiedAt));

        ClassifyAttemptFailureResult result = ClassifyAttemptFailureResult.builder()
                .fulfillment(toFulfillment(fulfillmentRecordMapper.selectById(command.getFulfillmentId())))
                .attempt(toFulfillmentAttempt(fulfillmentAttemptRecordMapper.selectById(command.getAttemptId())))
                .decision(decision)
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    @Transactional
    public ScheduleRetryAfterFailureResult scheduleRetryAfterFailure(ScheduleRetryAfterFailureCommand command) {
        IdempotencyRecord idempotencyRecord = beginIdempotentAction(
                "SCHEDULE_RETRY_AFTER_FAILURE", command.getFulfillmentId(), command.getIdempotencyKey(), command);
        if (!idempotencyRecord.isNewlyCreated()) {
            return replayOrReject(idempotencyRecord, ScheduleRetryAfterFailureResult.class);
        }

        FulfillmentRecord fulfillment = requireFulfillment(command.getFulfillmentId());
        ensureVersionMatches(fulfillment, command.getExpectedVersion());
        ensureFulfillmentStatus(fulfillment, "RETRY_PENDING");
        FailureDecision latestFailure = requireLatestFailure(fulfillment);
        if (!"RETRYABLE_TECHNICAL_FAILURE".equals(latestFailure.getCategory())) {
            throw new BusinessException(ErrorCode.FAILURE_CATEGORY_NOT_RETRYABLE);
        }

        OffsetDateTime now = requireNow(command.getNow());
        RetryPolicySnapshot retryPolicy = requireRetryPolicy(fulfillment);
        RetryState retryState = requireRetryState(fulfillment);
        List<GovernanceAuditRecord> emittedAuditRecords = new ArrayList<>();

        String scheduledAttemptTrigger;
        OffsetDateTime nextRetryAt;
        if (Boolean.TRUE.equals(retryState.getBudgetExhausted())
                || retryState.getTotalRetryUsed() >= retryPolicy.getTotalRetryBudget()) {
            return convergeRetryBudgetExhausted(command, idempotencyRecord, fulfillment, latestFailure, retryState, now);
        } else if ("FAST_RETRY".equals(command.getRequestedMode())) {
            if (!"ALLOW_FAST_RETRY".equals(latestFailure.getRetryDisposition())
                    || retryState.getFastRetryUsed() >= retryPolicy.getFastRetryLimit()) {
                throw new BusinessException(ErrorCode.FAST_RETRY_ALREADY_USED);
            }
            RetryState updatedState = RetryState.builder()
                    .fastRetryUsed(retryState.getFastRetryUsed() + 1)
                    .backoffRetryUsed(retryState.getBackoffRetryUsed())
                    .totalRetryUsed(retryState.getTotalRetryUsed() + 1)
                    .nextRetryAt(null)
                    .budgetExhausted(retryState.getTotalRetryUsed() + 1 >= retryPolicy.getTotalRetryBudget())
                    .build();
            fulfillment.setRetryStateJson(toJson(updatedState));
            updateFulfillment(fulfillment);
            emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), fulfillment.getLatestAttemptId(),
                    "FAST_RETRY_SCHEDULED", "RETRY_PENDING", "RETRY_PENDING", latestFailure, updatedState, now));
            scheduledAttemptTrigger = "FAST_RETRY";
            nextRetryAt = null;
        } else if ("BACKOFF_RETRY".equals(command.getRequestedMode())) {
            if (!"ALLOW_BACKOFF_RETRY".equals(latestFailure.getRetryDisposition())) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
            }
            if (retryState.getBackoffRetryUsed() >= retryPolicy.getBackoffRetryLimit()) {
                return convergeRetryBudgetExhausted(command, idempotencyRecord, fulfillment, latestFailure, retryState, now);
            } else {
                if (retryState.getNextRetryAt() != null && now.isBefore(retryState.getNextRetryAt())) {
                    throw new BusinessException(ErrorCode.NEXT_RETRY_NOT_DUE);
                }
                // Worker Decision: 退避档位数量必须与 policy.limit 对齐；脏配置统一按非法状态迁移处理，避免暴露未签约错误码。
                if (retryPolicy.getBackoffSchedule() == null
                        || retryState.getBackoffRetryUsed() >= retryPolicy.getBackoffSchedule().size()) {
                    throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
                }
                nextRetryAt = now.plus(parseDuration(retryPolicy.getBackoffSchedule().get(retryState.getBackoffRetryUsed())));
                RetryState updatedState = RetryState.builder()
                        .fastRetryUsed(retryState.getFastRetryUsed())
                        .backoffRetryUsed(retryState.getBackoffRetryUsed() + 1)
                        .totalRetryUsed(retryState.getTotalRetryUsed() + 1)
                        .nextRetryAt(nextRetryAt)
                        .budgetExhausted(retryState.getTotalRetryUsed() + 1 >= retryPolicy.getTotalRetryBudget())
                        .build();
                fulfillment.setRetryStateJson(toJson(updatedState));
                updateFulfillment(fulfillment);
                emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), fulfillment.getLatestAttemptId(),
                        "BACKOFF_RETRY_SCHEDULED", "RETRY_PENDING", "RETRY_PENDING", latestFailure, updatedState, now));
                scheduledAttemptTrigger = "BACKOFF_RETRY";
            }
        } else {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }

        ScheduleRetryAfterFailureResult result = ScheduleRetryAfterFailureResult.builder()
                .fulfillment(toFulfillment(fulfillmentRecordMapper.selectById(command.getFulfillmentId())))
                .scheduledAttemptTrigger(scheduledAttemptTrigger)
                .nextRetryAt(nextRetryAt)
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    @Transactional
    public StartRetryAttemptResult startRetryAttempt(StartRetryAttemptCommand command) {
        IdempotencyRecord idempotencyRecord = beginIdempotentAction(
                "START_RETRY_ATTEMPT", command.getFulfillmentId(), command.getIdempotencyKey(), command);
        if (!idempotencyRecord.isNewlyCreated()) {
            return replayOrReject(idempotencyRecord, StartRetryAttemptResult.class);
        }

        FulfillmentRecord fulfillment = requireFulfillment(command.getFulfillmentId());
        ensureVersionMatches(fulfillment, command.getExpectedVersion());
        ensureFulfillmentStatus(fulfillment, "RETRY_PENDING");
        if (!"FAST_RETRY".equals(command.getTrigger()) && !"BACKOFF_RETRY".equals(command.getTrigger())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }

        OffsetDateTime now = requireNow(command.getNow());
        RetryState retryState = requireRetryState(fulfillment);
        if ("BACKOFF_RETRY".equals(command.getTrigger())
                && retryState.getNextRetryAt() != null
                && now.isBefore(retryState.getNextRetryAt())) {
            throw new BusinessException(ErrorCode.NEXT_RETRY_NOT_DUE);
        }

        List<FulfillmentAttemptRecord> existingAttempts = fulfillmentAttemptRecordMapper.selectByFulfillmentId(command.getFulfillmentId());
        int nextSequence = existingAttempts.stream()
                .map(FulfillmentAttemptRecord::getAttemptNo)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;

        FulfillmentAttemptRecord attempt = new FulfillmentAttemptRecord();
        attempt.setAttemptId(UUID.randomUUID().toString());
        attempt.setFulfillmentId(command.getFulfillmentId());
        attempt.setAttemptNo(nextSequence);
        attempt.setTrigger(command.getTrigger());
        attempt.setExecutionStatus("STARTED");
        attempt.setStatus("EXECUTING");
        attempt.setDispatcherRunId("governance-retry");
        attempt.setExecutorRef("system");
        attempt.setExecutionPath(fulfillment.getExecutionPath());
        attempt.setClaimedAt(toLocalDateTime(now));
        attempt.setStartedAt(toLocalDateTime(now));
        fulfillmentAttemptRecordMapper.insert(attempt);

        RetryState updatedState = RetryState.builder()
                .fastRetryUsed(retryState.getFastRetryUsed())
                .backoffRetryUsed(retryState.getBackoffRetryUsed())
                .totalRetryUsed(retryState.getTotalRetryUsed())
                .nextRetryAt(null)
                .budgetExhausted(Boolean.TRUE.equals(retryState.getBudgetExhausted()))
                .build();
        fulfillment.setStatus("PROCESSING");
        fulfillment.setCurrentAttemptId(attempt.getAttemptId());
        fulfillment.setLatestAttemptId(attempt.getAttemptId());
        fulfillment.setProcessingStartedAt(toLocalDateTime(now));
        // Worker Decision: blueprint 未提供 lease 时长来源，这里固定为 15 分钟，并在 migration 中对遗留 PROCESSING 数据做同口径回填。
        fulfillment.setProcessingTimeoutAt(toLocalDateTime(now.plus(DEFAULT_PROCESSING_LEASE)));
        fulfillment.setRetryStateJson(toJson(updatedState));
        updateFulfillment(fulfillment);

        List<GovernanceAuditRecord> emittedAuditRecords = List.of(
                // Worker Decision: retry attempt 启动时沿用最近失败决策仅用于补全审计上下文；若此前并无失败历史，允许 reasonCode 为空而不额外制造未签约错误码。
                insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), "ATTEMPT_STARTED",
                        "RETRY_PENDING", "PROCESSING", readLatestFailure(fulfillmentRecordMapper.selectById(command.getFulfillmentId())),
                        updatedState, now)
        );
        StartRetryAttemptResult result = StartRetryAttemptResult.builder()
                .fulfillment(toFulfillment(fulfillmentRecordMapper.selectById(command.getFulfillmentId())))
                .attempt(toFulfillmentAttempt(fulfillmentAttemptRecordMapper.selectById(attempt.getAttemptId())))
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    @Transactional
    public GovernProcessingTimeoutResult governProcessingTimeout(GovernProcessingTimeoutCommand command) {
        IdempotencyRecord idempotencyRecord = beginIdempotentAction(
                "GOVERN_PROCESSING_TIMEOUT", command.getFulfillmentId(), command.getIdempotencyKey(), command);
        if (!idempotencyRecord.isNewlyCreated()) {
            return replayOrReject(idempotencyRecord, GovernProcessingTimeoutResult.class);
        }

        FulfillmentRecord fulfillment = requireFulfillment(command.getFulfillmentId());
        ensureVersionMatches(fulfillment, command.getExpectedVersion());
        ensureFulfillmentStatus(fulfillment, "PROCESSING");
        OffsetDateTime now = requireNow(command.getNow());
        OffsetDateTime timeoutAt = toOffsetDateTime(fulfillment.getProcessingTimeoutAt());
        if (timeoutAt == null || now.isBefore(timeoutAt)) {
            throw new BusinessException(ErrorCode.PROCESSING_NOT_TIMED_OUT);
        }

        FulfillmentAttemptRecord attempt = requireAttempt(fulfillment.getCurrentAttemptId(), command.getFulfillmentId());
        ensureAttemptStartedForTimeout(attempt);
        RetryState retryState = requireRetryState(fulfillment);
        RetryPolicySnapshot retryPolicy = requireRetryPolicy(fulfillment);
        FailureDecision decision = buildTimeoutDecision(command.getSafeToRetryEvidence(), retryState, retryPolicy, now);

        attempt.setExecutionStatus("ABANDONED");
        attempt.setStatus("ABANDONED");
        attempt.setFailureDecisionJson(toJson(decision));
        attempt.setFinishedAt(toLocalDateTime(now));
        updateAttempt(attempt);

        String fromStatus = fulfillment.getStatus();
        fulfillment.setCurrentAttemptId(null);
        fulfillment.setLatestAttemptId(attempt.getAttemptId());
        fulfillment.setProcessingStartedAt(null);
        fulfillment.setProcessingTimeoutAt(null);
        fulfillment.setLatestFailureDecisionJson(toJson(decision));
        RetryState updatedState = RetryState.builder()
                .fastRetryUsed(retryState.getFastRetryUsed())
                .backoffRetryUsed(retryState.getBackoffRetryUsed())
                .totalRetryUsed(retryState.getTotalRetryUsed())
                .nextRetryAt(null)
                .budgetExhausted(Boolean.TRUE.equals(retryState.getBudgetExhausted()))
                .build();
        fulfillment.setRetryStateJson(toJson(updatedState));
        fulfillment.setStatus("RETRY_PENDING".equals(nextStatusForTimeout(decision)) ? "RETRY_PENDING" : "MANUAL_PENDING");
        updateFulfillment(fulfillment);

        List<GovernanceAuditRecord> emittedAuditRecords = new ArrayList<>();
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), "ATTEMPT_ABANDONED",
                fromStatus, fromStatus, decision, updatedState, now));
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), "ATTEMPT_CLASSIFIED",
                fromStatus, fulfillment.getStatus(), decision, updatedState, now));
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), "PROCESSING_TIMEOUT_GOVERNED",
                fromStatus, fulfillment.getStatus(), decision, updatedState, now));
        emittedAuditRecords.add(insertAudit(command.getFulfillmentId(), attempt.getAttemptId(), transitionAction(fulfillment.getStatus()),
                fromStatus, fulfillment.getStatus(), decision, updatedState, now));

        GovernProcessingTimeoutResult result = GovernProcessingTimeoutResult.builder()
                .fulfillment(toFulfillment(fulfillmentRecordMapper.selectById(command.getFulfillmentId())))
                .attempt(toFulfillmentAttempt(fulfillmentAttemptRecordMapper.selectById(attempt.getAttemptId())))
                .decision(decision)
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    @Transactional
    public RecordAttemptSuccessResult recordAttemptSuccess(RecordAttemptSuccessCommand command) {
        IdempotencyRecord idempotencyRecord = beginIdempotentAction(
                "RECORD_ATTEMPT_SUCCESS", command.getFulfillmentId(), command.getIdempotencyKey(), command);
        if (!idempotencyRecord.isNewlyCreated()) {
            return replayOrReject(idempotencyRecord, RecordAttemptSuccessResult.class);
        }

        FulfillmentRecord fulfillment = requireFulfillment(command.getFulfillmentId());
        ensureVersionMatches(fulfillment, command.getExpectedVersion());
        ensureFulfillmentStatus(fulfillment, "PROCESSING");
        FulfillmentAttemptRecord attempt = requireAttempt(command.getAttemptId(), command.getFulfillmentId());
        ensureAttemptActive(fulfillment, attempt, command.getAttemptId());
        ensureAttemptStarted(attempt);
        OffsetDateTime now = requireNow(command.getNow());

        attempt.setExecutionStatus("SUCCEEDED");
        attempt.setStatus("SUCCEEDED");
        attempt.setFinishedAt(toLocalDateTime(now));
        updateAttempt(attempt);

        fulfillment.setStatus("SUCCEEDED");
        fulfillment.setCurrentAttemptId(null);
        fulfillment.setLatestAttemptId(command.getAttemptId());
        fulfillment.setProcessingStartedAt(null);
        fulfillment.setProcessingTimeoutAt(null);
        fulfillment.setTerminalAt(toLocalDateTime(now));
        updateFulfillment(fulfillment);

        FulfillmentRecord reloadedFulfillment = fulfillmentRecordMapper.selectById(command.getFulfillmentId());
        RetryState retryState = requireRetryState(reloadedFulfillment);
        List<GovernanceAuditRecord> emittedAuditRecords = List.of(
                // Worker Decision: 首次执行即成功时 latestFailure 本来就可能为空；这里允许空值透传到审计，避免把“无失败历史”误判成状态损坏。
                insertAudit(command.getFulfillmentId(), command.getAttemptId(), "MOVED_TO_SUCCEEDED",
                        "PROCESSING", "SUCCEEDED", readLatestFailure(reloadedFulfillment),
                        retryState, now)
        );
        RecordAttemptSuccessResult result = RecordAttemptSuccessResult.builder()
                .fulfillment(toFulfillment(reloadedFulfillment))
                .attempt(toFulfillmentAttempt(fulfillmentAttemptRecordMapper.selectById(command.getAttemptId())))
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    public GetFulfillmentGovernanceViewResult getFulfillmentGovernanceView(GetFulfillmentGovernanceViewQuery query) {
        FulfillmentRecord fulfillment = requireFulfillment(query.getFulfillmentId());
        FulfillmentAttemptRecord latestAttempt = null;
        if (fulfillment.getLatestAttemptId() != null) {
            latestAttempt = fulfillmentAttemptRecordMapper.selectById(fulfillment.getLatestAttemptId());
        }
        List<GovernanceAuditRecord> recentAuditRecords = governanceAuditRecordMapper
                .selectRecentByFulfillmentId(query.getFulfillmentId(), RECENT_AUDIT_LIMIT)
                .stream()
                .map(this::toGovernanceAuditRecordForView)
                .toList();
        return GetFulfillmentGovernanceViewResult.builder()
                .fulfillment(toFulfillmentForView(fulfillment))
                .latestAttempt(toFulfillmentAttemptForView(latestAttempt))
                .latestFailure(readJsonLenient(fulfillment.getLatestFailureDecisionJson(), FailureDecision.class))
                .retryState(resolveRetryStateForView(fulfillment))
                .recentAuditRecords(recentAuditRecords)
                .build();
    }

    private ScheduleRetryAfterFailureResult convergeRetryBudgetExhausted(ScheduleRetryAfterFailureCommand command,
                                                                         IdempotencyRecord idempotencyRecord,
                                                                         FulfillmentRecord fulfillment,
                                                                         FailureDecision latestFailure,
                                                                         RetryState retryState,
                                                                         OffsetDateTime now) {
        RetryState updatedState = RetryState.builder()
                .fastRetryUsed(retryState.getFastRetryUsed())
                .backoffRetryUsed(retryState.getBackoffRetryUsed())
                .totalRetryUsed(retryState.getTotalRetryUsed())
                .nextRetryAt(null)
                .budgetExhausted(true)
                .build();
        fulfillment.setStatus("MANUAL_PENDING");
        fulfillment.setRetryStateJson(toJson(updatedState));
        updateFulfillment(fulfillment);

        List<GovernanceAuditRecord> emittedAuditRecords = List.of(
                insertAudit(command.getFulfillmentId(), fulfillment.getLatestAttemptId(),
                        "MOVED_TO_MANUAL_PENDING", "RETRY_PENDING", "MANUAL_PENDING", latestFailure, updatedState, now)
        );
        ScheduleRetryAfterFailureResult result = ScheduleRetryAfterFailureResult.builder()
                .fulfillment(toFulfillment(fulfillmentRecordMapper.selectById(command.getFulfillmentId())))
                .scheduledAttemptTrigger(null)
                .nextRetryAt(null)
                .emittedAuditRecords(emittedAuditRecords)
                .build();
        idempotencyService.markSucceeded(idempotencyRecord.getIdempotencyRecordId(), "FULFILLMENT", command.getFulfillmentId(), result);
        return result;
    }

    private IdempotencyRecord beginIdempotentAction(String operationType, String fulfillmentId, String idempotencyKey, Object request) {
        String requestHash = idempotencyService.hashRequest(request);
        return idempotencyService.checkAndMarkProcessing(actionName(operationType, fulfillmentId), idempotencyKey, requestHash, fulfillmentId);
    }

    private <T> T replayOrReject(IdempotencyRecord idempotencyRecord, Class<T> responseClass) {
        if ("SUCCEEDED".equals(idempotencyRecord.getStatus())) {
            return idempotencyService.replayResponse(idempotencyRecord, responseClass);
        }
        throw new BusinessException(ErrorCode.IDEMPOTENT_REPLAY_CONFLICT);
    }

    private String actionName(String operationType, String fulfillmentId) {
        return operationType + ":" + fulfillmentId;
    }

    private FulfillmentRecord requireFulfillment(String fulfillmentId) {
        FulfillmentRecord fulfillment = fulfillmentRecordMapper.selectById(fulfillmentId);
        if (fulfillment == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_NOT_FOUND);
        }
        return fulfillment;
    }

    private FulfillmentAttemptRecord requireAttempt(String attemptId, String fulfillmentId) {
        FulfillmentAttemptRecord attempt = fulfillmentAttemptRecordMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(attempt.getFulfillmentId(), fulfillmentId)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        return attempt;
    }

    private void ensureVersionMatches(FulfillmentRecord fulfillment, Long expectedVersion) {
        if (expectedVersion == null || !Objects.equals(fulfillment.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.CONCURRENCY_VERSION_MISMATCH);
        }
    }

    private void ensureFulfillmentStatus(FulfillmentRecord fulfillment, String expectedStatus) {
        if (!expectedStatus.equals(fulfillment.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private void ensureAttemptActive(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt, String attemptId) {
        if (!Objects.equals(fulfillment.getCurrentAttemptId(), attemptId)
                || !Objects.equals(attempt.getAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private void ensureAttemptStarted(FulfillmentAttemptRecord attempt) {
        if (!"STARTED".equals(attempt.getExecutionStatus())) {
            throw new BusinessException(ErrorCode.ATTEMPT_ALREADY_FINALIZED);
        }
    }

    private void ensureAttemptStartedForTimeout(FulfillmentAttemptRecord attempt) {
        if (!"STARTED".equals(attempt.getExecutionStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private void updateFulfillment(FulfillmentRecord fulfillment) {
        if (fulfillmentRecordMapper.updateById(fulfillment) == 0) {
            throw new BusinessException(ErrorCode.CONCURRENCY_VERSION_MISMATCH);
        }
    }

    private void updateAttempt(FulfillmentAttemptRecord attempt) {
        if (fulfillmentAttemptRecordMapper.updateById(attempt) == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private FailureDecision classifyObservedFailure(ObservedFailure observedFailure,
                                                    RetryState retryState,
                                                    RetryPolicySnapshot retryPolicy,
                                                    OffsetDateTime classifiedAt) {
        if (observedFailure == null || observedFailure.getFailureSignal() == null || observedFailure.getFailureSignal().isBlank()) {
            throw new BusinessException(ErrorCode.FAILURE_DECISION_REQUIRED);
        }
        return switch (observedFailure.getFailureSignal()) {
            case "NETWORK_TIMEOUT", "GATEWAY_TEMPORARY_ERROR", "PROVIDER_RATE_LIMITED",
                 "PROVIDER_TEMPORARILY_UNAVAILABLE" -> FailureDecision.builder()
                    .category("RETRYABLE_TECHNICAL_FAILURE")
                    .reasonCode(observedFailure.getFailureSignal())
                    // Worker Decision: 分类阶段只决定“最快允许的自动恢复轨道”，真正的预算扣减与耗尽退出统一放在 ScheduleRetryAfterFailure 中处理。
                    .retryDisposition(preferredRetryDisposition(retryState, retryPolicy))
                    .manualReviewRequired(false)
                    .finalTerminationSuggested(false)
                    .rationale("Observed provider failure is classified as retryable technical failure.")
                    .classifiedAt(classifiedAt)
                    .build();
            case "FULFILLMENT_WINDOW_EXPIRED", "ORDER_CONDITION_INVALID", "PROVIDER_PERMANENT_REJECTED" -> FailureDecision.builder()
                    .category("FINAL_BUSINESS_REJECTED")
                    .reasonCode(observedFailure.getFailureSignal())
                    .retryDisposition("STOP_AND_FINAL_FAIL")
                    .manualReviewRequired(false)
                    .finalTerminationSuggested(true)
                    .rationale("Observed provider or business signal indicates a final business rejection.")
                    .classifiedAt(classifiedAt)
                    .build();
            case "UPSTREAM_DATA_REQUIRES_REVIEW", "MANUAL_SOURCE_SWITCH_REQUIRED" -> FailureDecision.builder()
                    .category("MANUAL_REVIEW_REQUIRED")
                    .reasonCode(observedFailure.getFailureSignal())
                    .retryDisposition("STOP_AND_MANUAL")
                    .manualReviewRequired(true)
                    .finalTerminationSuggested(false)
                    .rationale("Observed signal requires operator review before any further action.")
                    .classifiedAt(classifiedAt)
                    .build();
            case "EXTERNAL_RESULT_UNKNOWN" -> FailureDecision.builder()
                    .category("UNCERTAIN_RESULT")
                    .reasonCode("EXTERNAL_RESULT_UNKNOWN")
                    .retryDisposition("STOP_AND_MANUAL")
                    .manualReviewRequired(true)
                    .finalTerminationSuggested(false)
                    .rationale("The external outcome is unknown and automatic retry is therefore blocked.")
                    .classifiedAt(classifiedAt)
                    .build();
            default -> throw new BusinessException(ErrorCode.FAILURE_DECISION_REQUIRED);
        };
    }

    private FailureDecision buildTimeoutDecision(SafeToRetryEvidence evidence,
                                                 RetryState retryState,
                                                 RetryPolicySnapshot retryPolicy,
                                                 OffsetDateTime now) {
        boolean confirmedNotSucceeded = evidence != null && Boolean.TRUE.equals(evidence.getConfirmedNotSucceeded());
        boolean duplicateRiskControllable = evidence != null && Boolean.TRUE.equals(evidence.getDuplicateExecutionRiskControllable());
        if (confirmedNotSucceeded && duplicateRiskControllable) {
            return FailureDecision.builder()
                    .category("RETRYABLE_TECHNICAL_FAILURE")
                    .reasonCode("PROCESSING_STUCK_SAFE_TO_RETRY")
                    .retryDisposition(preferredRetryDisposition(retryState, retryPolicy))
                    .manualReviewRequired(false)
                    .finalTerminationSuggested(false)
                    .rationale("Processing timed out with sufficient evidence that automatic retry remains safe.")
                    .classifiedAt(now)
                    .build();
        }
        String reasonCode = confirmedNotSucceeded ? "PROCESSING_STUCK_UNSAFE_TO_RETRY" : "EXTERNAL_RESULT_UNKNOWN";
        return FailureDecision.builder()
                .category("UNCERTAIN_RESULT")
                .reasonCode(reasonCode)
                .retryDisposition("STOP_AND_MANUAL")
                .manualReviewRequired(true)
                .finalTerminationSuggested(false)
                .rationale("Processing timed out without sufficient safety evidence for another automatic step.")
                .classifiedAt(now)
                .build();
    }

    private String preferredRetryDisposition(RetryState retryState, RetryPolicySnapshot retryPolicy) {
        if (retryState.getFastRetryUsed() < retryPolicy.getFastRetryLimit()) {
            return "ALLOW_FAST_RETRY";
        }
        return "ALLOW_BACKOFF_RETRY";
    }

    private String nextStatusForTimeout(FailureDecision decision) {
        return "RETRYABLE_TECHNICAL_FAILURE".equals(decision.getCategory()) ? "RETRY_PENDING" : "MANUAL_PENDING";
    }

    private String transitionAction(String toStatus) {
        return switch (toStatus) {
            case "RETRY_PENDING" -> "MOVED_TO_RETRY_PENDING";
            case "MANUAL_PENDING" -> "MOVED_TO_MANUAL_PENDING";
            case "FAILED" -> "MOVED_TO_FAILED";
            case "SUCCEEDED" -> "MOVED_TO_SUCCEEDED";
            default -> throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        };
    }

    private GovernanceAuditRecord insertAudit(String fulfillmentId,
                                              String attemptId,
                                              String actionType,
                                              String fromStatus,
                                              String toStatus,
                                              FailureDecision decision,
                                              RetryState retryState,
                                              OffsetDateTime occurredAt) {
        FulfillmentGovernanceAuditRecordEntity entity = new FulfillmentGovernanceAuditRecordEntity();
        entity.setAuditId(UUID.randomUUID().toString());
        entity.setFulfillmentId(fulfillmentId);
        entity.setAttemptId(attemptId);
        entity.setActionType(actionType);
        entity.setFromStatus(fromStatus);
        entity.setToStatus(toStatus);
        entity.setFailureCategory(decision == null ? null : decision.getCategory());
        entity.setReasonCode(decision == null ? null : decision.getReasonCode());
        entity.setRetryBudgetSnapshotJson(toJson(retryState));
        entity.setActorType("SYSTEM");
        entity.setOccurredAt(toLocalDateTime(occurredAt));
        governanceAuditRecordMapper.insert(entity);
        return toGovernanceAuditRecord(entity);
    }

    private RetryPolicySnapshot requireRetryPolicy(FulfillmentRecord fulfillment) {
        RetryPolicySnapshot retryPolicy = readJson(fulfillment.getRetryPolicyJson(), RetryPolicySnapshot.class);
        if (retryPolicy == null) {
            // Worker Decision: blueprint 未定义 retryPolicy 缺失/脏数据的专用错误码；这里统一收敛到 INVALID_STATUS_TRANSITION，避免对外扩散新的契约面。
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        return retryPolicy;
    }

    private RetryState requireRetryState(FulfillmentRecord fulfillment) {
        RetryState retryState = readJson(fulfillment.getRetryStateJson(), RetryState.class);
        if (retryState == null) {
            // Worker Decision: blueprint 未定义 retryState 缺失/脏数据的专用错误码；这里统一收敛到 INVALID_STATUS_TRANSITION，避免把存量坏数据升级成新错误类型。
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        return retryState;
    }

    private FailureDecision requireLatestFailure(FulfillmentRecord fulfillment) {
        FailureDecision failureDecision = readJson(fulfillment.getLatestFailureDecisionJson(), FailureDecision.class);
        if (failureDecision == null) {
            throw new BusinessException(ErrorCode.FAILURE_DECISION_REQUIRED);
        }
        return failureDecision;
    }

    private FailureDecision readLatestFailure(FulfillmentRecord fulfillment) {
        return readJson(fulfillment.getLatestFailureDecisionJson(), FailureDecision.class);
    }

    private OffsetDateTime requireNow(OffsetDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return now;
    }

    private Duration parseDuration(String durationLiteral) {
        return Duration.parse(durationLiteral);
    }

    private Fulfillment toFulfillment(FulfillmentRecord record) {
        if (record == null) {
            return null;
        }
        return Fulfillment.builder()
                .fulfillmentId(record.getFulfillmentId())
                .orderId(record.getOrderId())
                .status(record.getStatus())
                .currentAttemptId(record.getCurrentAttemptId())
                .latestAttemptId(record.getLatestAttemptId())
                .processingStartedAt(toOffsetDateTime(record.getProcessingStartedAt()))
                .processingTimeoutAt(toOffsetDateTime(record.getProcessingTimeoutAt()))
                .terminalAt(toOffsetDateTime(record.getTerminalAt()))
                .executionPath(record.getExecutionPath())
                .deliveryResult(readJson(record.getDeliveryResultJson(), DeliveryResult.class))
                .lastFailure(readJson(record.getLastFailureJson(), FailureSummary.class))
                .lastDiagnosticTrace(readJson(record.getLastDiagnosticTraceJson(), DiagnosticTrace.class))
                .retryPolicy(readJson(record.getRetryPolicyJson(), RetryPolicySnapshot.class))
                .retryState(readJson(record.getRetryStateJson(), RetryState.class))
                // Worker Decision: 只有 lease 两个关键时间戳至少存在一个时才返回 lease 视图，避免对外暴露一个没有语义的空壳对象。
                .currentProcessingLease(record.getProcessingStartedAt() == null && record.getProcessingTimeoutAt() == null
                        ? null
                        : ProcessingLease.builder()
                        .processingStartedAt(toOffsetDateTime(record.getProcessingStartedAt()))
                        .timeoutAt(toOffsetDateTime(record.getProcessingTimeoutAt()))
                        .build())
                .latestFailure(readJson(record.getLatestFailureDecisionJson(), FailureDecision.class))
                .version(record.getVersion())
                .createdAt(toOffsetDateTime(record.getCreatedAt()))
                .updatedAt(toOffsetDateTime(record.getUpdatedAt()))
                .build();
    }

    private Fulfillment toFulfillmentForView(FulfillmentRecord record) {
        if (record == null) {
            return null;
        }
        return Fulfillment.builder()
                .fulfillmentId(record.getFulfillmentId())
                .orderId(record.getOrderId())
                .status(record.getStatus())
                .currentAttemptId(record.getCurrentAttemptId())
                .latestAttemptId(record.getLatestAttemptId())
                .processingStartedAt(toOffsetDateTime(record.getProcessingStartedAt()))
                .processingTimeoutAt(toOffsetDateTime(record.getProcessingTimeoutAt()))
                .terminalAt(toOffsetDateTime(record.getTerminalAt()))
                .executionPath(record.getExecutionPath())
                .deliveryResult(readJsonLenient(record.getDeliveryResultJson(), DeliveryResult.class))
                .lastFailure(readJsonLenient(record.getLastFailureJson(), FailureSummary.class))
                .lastDiagnosticTrace(readJsonLenient(record.getLastDiagnosticTraceJson(), DiagnosticTrace.class))
                .retryPolicy(readJsonLenient(record.getRetryPolicyJson(), RetryPolicySnapshot.class))
                // Worker Decision: 查询接口只签了 FULFILLMENT_NOT_FOUND；存量 JSON 坏数据在读取视图时降级为空/默认值，避免把内部脏状态扩成新的对外错误码。
                .retryState(resolveRetryStateForView(record))
                .currentProcessingLease(record.getProcessingStartedAt() == null && record.getProcessingTimeoutAt() == null
                        ? null
                        : ProcessingLease.builder()
                        .processingStartedAt(toOffsetDateTime(record.getProcessingStartedAt()))
                        .timeoutAt(toOffsetDateTime(record.getProcessingTimeoutAt()))
                        .build())
                .latestFailure(readJsonLenient(record.getLatestFailureDecisionJson(), FailureDecision.class))
                .version(record.getVersion())
                .createdAt(toOffsetDateTime(record.getCreatedAt()))
                .updatedAt(toOffsetDateTime(record.getUpdatedAt()))
                .build();
    }

    private FulfillmentAttempt toFulfillmentAttempt(FulfillmentAttemptRecord record) {
        if (record == null) {
            return null;
        }
        return FulfillmentAttempt.builder()
                .attemptId(record.getAttemptId())
                .fulfillmentId(record.getFulfillmentId())
                .attemptNo(record.getAttemptNo())
                .sequenceNo(record.getAttemptNo())
                .trigger(record.getTrigger())
                .executionStatus(record.getExecutionStatus())
                .status(record.getStatus())
                .dispatcherRunId(record.getDispatcherRunId())
                .executorRef(record.getExecutorRef())
                .executionPath(record.getExecutionPath())
                .claimedAt(toOffsetDateTime(record.getClaimedAt()))
                .startedAt(toOffsetDateTime(record.getStartedAt()))
                .endedAt(toOffsetDateTime(record.getFinishedAt()))
                .finishedAt(toOffsetDateTime(record.getFinishedAt()))
                .deliveryResult(readJson(record.getDeliveryResultJson(), DeliveryResult.class))
                .failure(readJson(record.getFailureJson(), FailureSummary.class))
                .failureDecision(readJson(record.getFailureDecisionJson(), FailureDecision.class))
                .providerDiagnostic(readJson(record.getProviderDiagnosticJson(), ProviderDiagnostic.class))
                .diagnosticTrace(readJson(record.getDiagnosticTraceJson(), DiagnosticTrace.class))
                .build();
    }

    private FulfillmentAttempt toFulfillmentAttemptForView(FulfillmentAttemptRecord record) {
        if (record == null) {
            return null;
        }
        return FulfillmentAttempt.builder()
                .attemptId(record.getAttemptId())
                .fulfillmentId(record.getFulfillmentId())
                .attemptNo(record.getAttemptNo())
                .sequenceNo(record.getAttemptNo())
                .trigger(record.getTrigger())
                .executionStatus(record.getExecutionStatus())
                .status(record.getStatus())
                .dispatcherRunId(record.getDispatcherRunId())
                .executorRef(record.getExecutorRef())
                .executionPath(record.getExecutionPath())
                .claimedAt(toOffsetDateTime(record.getClaimedAt()))
                .startedAt(toOffsetDateTime(record.getStartedAt()))
                .endedAt(toOffsetDateTime(record.getFinishedAt()))
                .finishedAt(toOffsetDateTime(record.getFinishedAt()))
                .deliveryResult(readJsonLenient(record.getDeliveryResultJson(), DeliveryResult.class))
                .failure(readJsonLenient(record.getFailureJson(), FailureSummary.class))
                .failureDecision(readJsonLenient(record.getFailureDecisionJson(), FailureDecision.class))
                .providerDiagnostic(readJsonLenient(record.getProviderDiagnosticJson(), ProviderDiagnostic.class))
                .diagnosticTrace(readJsonLenient(record.getDiagnosticTraceJson(), DiagnosticTrace.class))
                .build();
    }

    private GovernanceAuditRecord toGovernanceAuditRecord(FulfillmentGovernanceAuditRecordEntity entity) {
        return GovernanceAuditRecord.builder()
                .auditId(entity.getAuditId())
                .fulfillmentId(entity.getFulfillmentId())
                .attemptId(entity.getAttemptId())
                .actionType(entity.getActionType())
                .fromStatus(entity.getFromStatus())
                .toStatus(entity.getToStatus())
                .failureCategory(entity.getFailureCategory())
                .reasonCode(entity.getReasonCode())
                .retryBudgetSnapshot(readJson(entity.getRetryBudgetSnapshotJson(), RetryState.class))
                .actorType(entity.getActorType())
                .occurredAt(toOffsetDateTime(entity.getOccurredAt()))
                .build();
    }

    private GovernanceAuditRecord toGovernanceAuditRecordForView(FulfillmentGovernanceAuditRecordEntity entity) {
        return GovernanceAuditRecord.builder()
                .auditId(entity.getAuditId())
                .fulfillmentId(entity.getFulfillmentId())
                .attemptId(entity.getAttemptId())
                .actionType(entity.getActionType())
                .fromStatus(entity.getFromStatus())
                .toStatus(entity.getToStatus())
                .failureCategory(entity.getFailureCategory())
                .reasonCode(entity.getReasonCode())
                .retryBudgetSnapshot(readJsonLenient(entity.getRetryBudgetSnapshotJson(), RetryState.class))
                .actorType(entity.getActorType())
                .occurredAt(toOffsetDateTime(entity.getOccurredAt()))
                .build();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON payload", e);
        }
    }

    private <T> T readJson(String json, Class<T> targetType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, targetType);
        } catch (IOException e) {
            // Worker Decision: JSON 反序列化失败本质上也是聚合状态损坏；统一映射为 INVALID_STATUS_TRANSITION，保持对外错误码集合仍然受 Blueprint 约束。
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
    }

    private <T> T readJsonLenient(String json, Class<T> targetType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, targetType);
        } catch (IOException e) {
            return null;
        }
    }

    private RetryState resolveRetryStateForView(FulfillmentRecord fulfillment) {
        RetryState retryState = readJsonLenient(fulfillment.getRetryStateJson(), RetryState.class);
        return retryState == null ? emptyRetryState() : retryState;
    }

    private RetryState emptyRetryState() {
        return RetryState.builder()
                .fastRetryUsed(0)
                .backoffRetryUsed(0)
                .totalRetryUsed(0)
                .nextRetryAt(null)
                .budgetExhausted(false)
                .build();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp.atOffset(ZoneOffset.UTC);
    }
}
