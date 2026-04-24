package com.ticket.core.fulfillment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.service.AuditTrailService;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.fulfillment.dto.*;
import com.ticket.core.fulfillment.entity.FulfillmentAttemptRecord;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentAttemptRecordMapper;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class FulfillmentService {

    private static final String EXECUTION_PATH_DEFAULT_PROVIDER = "DEFAULT_PROVIDER";

    private final FulfillmentRecordMapper fulfillmentRecordMapper;
    private final FulfillmentAttemptRecordMapper fulfillmentAttemptRecordMapper;
    private final TicketOrderMapper ticketOrderMapper;
    private final ObjectMapper objectMapper;
    private final AuditTrailService auditTrailService;

    public FulfillmentService(FulfillmentRecordMapper fulfillmentRecordMapper,
                              FulfillmentAttemptRecordMapper fulfillmentAttemptRecordMapper,
                              TicketOrderMapper ticketOrderMapper,
                              ObjectMapper objectMapper,
                              AuditTrailService auditTrailService) {
        this.fulfillmentRecordMapper = fulfillmentRecordMapper;
        this.fulfillmentAttemptRecordMapper = fulfillmentAttemptRecordMapper;
        this.ticketOrderMapper = ticketOrderMapper;
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper.copy()).findAndRegisterModules();
        this.auditTrailService = auditTrailService;
    }

    public FulfillmentService(FulfillmentRecordMapper fulfillmentRecordMapper) {
        this(fulfillmentRecordMapper, null, null, new ObjectMapper(), null);
    }

    public FulfillmentRecord findByOrderId(String orderId) {
        return fulfillmentRecordMapper.selectOne(new LambdaQueryWrapper<FulfillmentRecord>()
                .eq(FulfillmentRecord::getOrderId, orderId));
    }

    public void create(FulfillmentRecord fulfillmentRecord) {
        fulfillmentRecordMapper.insert(fulfillmentRecord);
    }

    @Transactional
    public ListDispatchableFulfillmentsResponse listDispatchableFulfillments(ListDispatchableFulfillmentsRequest request) {
        requirePositive(request.getBatchSize(), "batchSize");
        Cursor cursor = decodeCursor(request.getCursor());
        LocalDateTime scanObservedAt = LocalDateTime.now(ZoneOffset.UTC);
        List<FulfillmentRecord> records = fulfillmentRecordMapper.selectDispatchableBatch(
                cursor.createdAt(), cursor.fulfillmentId(), request.getBatchSize());
        List<DispatchableFulfillmentCandidate> candidates = records.stream()
                .map(this::toDispatchableCandidate)
                .toList();
        for (DispatchableFulfillmentCandidate candidate : candidates) {
            appendDispatchCandidateAudit(candidate, request, scanObservedAt);
        }
        String nextCursor = candidates.isEmpty() ? null : encodeCursor(records.get(records.size() - 1));
        return ListDispatchableFulfillmentsResponse.builder()
                .candidates(candidates)
                .nextCursor(nextCursor)
                .build();
    }

    public GetFulfillmentExecutionSnapshotResponse getFulfillmentExecutionSnapshot(GetFulfillmentExecutionSnapshotRequest request) {
        FulfillmentRecord fulfillmentRecord = fulfillmentRecordMapper.selectById(request.getFulfillmentId());
        if (fulfillmentRecord == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_NOT_FOUND);
        }
        List<FulfillmentAttemptRecord> attempts = fulfillmentAttemptRecordMapper.selectByFulfillmentId(request.getFulfillmentId());
        return GetFulfillmentExecutionSnapshotResponse.builder()
                .fulfillment(toFulfillmentDto(fulfillmentRecord))
                .attempts(attempts.stream().map(this::toFulfillmentAttemptDto).toList())
                .build();
    }

    public ListStuckProcessingFulfillmentsResponse listStuckProcessingFulfillments(ListStuckProcessingFulfillmentsRequest request) {
        requirePositive(request.getBatchSize(), "batchSize");
        if (request.getOlderThan() == null || request.getOlderThan().isNegative() || request.getOlderThan().isZero()) {
            throw new IllegalArgumentException("olderThan must be greater than zero");
        }
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minus(request.getOlderThan());
        List<StuckProcessingFulfillment> items = fulfillmentRecordMapper.selectStuckProcessingBatch(threshold, request.getBatchSize())
                .stream()
                .map(record -> StuckProcessingFulfillment.builder()
                        .fulfillmentId(record.getFulfillmentId())
                        .orderId(record.getOrderId())
                        .currentAttemptId(record.getCurrentAttemptId())
                        .processingStartedAt(toOffset(record.getProcessingStartedAt()))
                        .diagnosticTrace(readJson(record.getLastDiagnosticTraceJson(), DiagnosticTrace.class))
                        .build())
                .toList();
        return ListStuckProcessingFulfillmentsResponse.builder()
                .items(items)
                .build();
    }

    @Transactional
    public ClaimFulfillmentForProcessingResponse claimFulfillmentForProcessing(ClaimFulfillmentForProcessingRequest request) {
        validateClaimRequest(request);
        FulfillmentRecord fulfillment = requireFulfillment(request.getFulfillmentId());
        ensureClaimableState(fulfillment);

        String attemptId = UUID.randomUUID().toString();
        LocalDateTime claimedAt = toLocalDateTime(request.getClaimedAt());
        String diagnosticTraceJson = toJson(request.getDiagnosticTrace());

        FulfillmentAttemptRecord attemptRecord = new FulfillmentAttemptRecord();
        attemptRecord.setAttemptId(attemptId);
        attemptRecord.setFulfillmentId(request.getFulfillmentId());
        attemptRecord.setAttemptNo(1);
        attemptRecord.setStatus("EXECUTING");
        attemptRecord.setDispatcherRunId(request.getDispatcherRunId());
        attemptRecord.setExecutorRef(request.getExecutorRef());
        attemptRecord.setExecutionPath(request.getExecutionPath());
        attemptRecord.setClaimedAt(claimedAt);
        attemptRecord.setStartedAt(claimedAt);
        attemptRecord.setDiagnosticTraceJson(diagnosticTraceJson);
        fulfillmentAttemptRecordMapper.insert(attemptRecord);

        int updated = fulfillmentRecordMapper.claimForProcessing(
                request.getFulfillmentId(),
                attemptId,
                claimedAt,
                request.getExecutionPath(),
                diagnosticTraceJson,
                request.getExpectedVersion());
        if (updated == 0) {
            throw resolveClaimFailure(request.getFulfillmentId(), request.getExpectedVersion());
        }

        appendProcessingStartedAudit(fulfillment, attemptRecord, request.getDiagnosticTrace(), claimedAt);
        return ClaimFulfillmentForProcessingResponse.builder()
                .fulfillmentId(request.getFulfillmentId())
                .attemptId(attemptId)
                .status("PROCESSING")
                .processingStartedAt(request.getClaimedAt())
                .build();
    }

    @Transactional
    public MarkFulfillmentSucceededResponse markFulfillmentSucceeded(MarkFulfillmentSucceededRequest request) {
        DeliveryResult deliveryResult = requireDeliveryResult(request.getDeliveryResult());
        DiagnosticTrace diagnosticTrace = requireDiagnosticTrace(request.getDiagnosticTrace());
        FulfillmentStateContext context = loadProcessingContext(request.getFulfillmentId(), request.getAttemptId());
        LocalDateTime terminalAt = toLocalDateTime(request.getSucceededAt());
        String deliveryResultJson = toJson(deliveryResult);
        String diagnosticTraceJson = toJson(diagnosticTrace);

        int updatedFulfillment = fulfillmentRecordMapper.markSucceeded(
                request.getFulfillmentId(),
                request.getAttemptId(),
                terminalAt,
                deliveryResultJson,
                diagnosticTraceJson,
                request.getExpectedVersion());
        if (updatedFulfillment == 0) {
            throw resolveMutationFailure(request.getFulfillmentId(), request.getAttemptId());
        }
        int updatedAttempt = fulfillmentAttemptRecordMapper.markSucceeded(
                request.getFulfillmentId(),
                request.getAttemptId(),
                terminalAt,
                deliveryResultJson,
                diagnosticTraceJson);
        if (updatedAttempt == 0) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }

        appendSucceededAudit(context.fulfillment(), context.attempt(), deliveryResult, diagnosticTrace, terminalAt);
        return MarkFulfillmentSucceededResponse.builder()
                .fulfillmentId(request.getFulfillmentId())
                .attemptId(request.getAttemptId())
                .status("SUCCEEDED")
                .terminalAt(request.getSucceededAt())
                .build();
    }

    @Transactional
    public MarkFulfillmentFailedResponse markFulfillmentFailed(MarkFulfillmentFailedRequest request) {
        FailureSummary failureSummary = requireFailureSummary(request.getFailure());
        DiagnosticTrace diagnosticTrace = requireDiagnosticTrace(request.getDiagnosticTrace());
        FulfillmentStateContext context = loadProcessingContext(request.getFulfillmentId(), request.getAttemptId());
        LocalDateTime terminalAt = toLocalDateTime(request.getFailedAt());
        String failureJson = toJson(failureSummary);
        String diagnosticTraceJson = toJson(diagnosticTrace);

        int updatedFulfillment = fulfillmentRecordMapper.markFailed(
                request.getFulfillmentId(),
                request.getAttemptId(),
                terminalAt,
                failureJson,
                diagnosticTraceJson,
                request.getExpectedVersion());
        if (updatedFulfillment == 0) {
            throw resolveMutationFailure(request.getFulfillmentId(), request.getAttemptId());
        }
        int updatedAttempt = fulfillmentAttemptRecordMapper.markFailed(
                request.getFulfillmentId(),
                request.getAttemptId(),
                terminalAt,
                failureJson,
                diagnosticTraceJson);
        if (updatedAttempt == 0) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }

        appendFailedAudit(context.fulfillment(), context.attempt(), failureSummary, diagnosticTrace, terminalAt);
        return MarkFulfillmentFailedResponse.builder()
                .fulfillmentId(request.getFulfillmentId())
                .attemptId(request.getAttemptId())
                .status("FAILED")
                .terminalAt(request.getFailedAt())
                .build();
    }

    @Transactional
    public RecordFulfillmentResultLeftProcessingResponse recordFulfillmentResultLeftProcessing(
            RecordFulfillmentResultLeftProcessingRequest request) {
        DiagnosticTrace diagnosticTrace = requireDiagnosticTrace(request.getDiagnosticTrace());
        if (!"RESULT_LEFT_PROCESSING".equals(diagnosticTrace.getDecision())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        FulfillmentStateContext context = loadProcessingContext(request.getFulfillmentId(), request.getAttemptId());
        String diagnosticTraceJson = toJson(diagnosticTrace);

        int updatedFulfillment = fulfillmentRecordMapper.recordLeftProcessing(
                request.getFulfillmentId(),
                request.getAttemptId(),
                diagnosticTraceJson,
                request.getExpectedVersion());
        if (updatedFulfillment == 0) {
            throw resolveMutationFailure(request.getFulfillmentId(), request.getAttemptId());
        }
        int updatedAttempt = fulfillmentAttemptRecordMapper.recordLeftProcessing(
                request.getFulfillmentId(),
                request.getAttemptId(),
                diagnosticTraceJson);
        if (updatedAttempt == 0) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }

        LocalDateTime observedAt = toLocalDateTime(request.getObservedAt());
        appendLeftProcessingAudit(context.fulfillment(), context.attempt(), diagnosticTrace, observedAt);
        return RecordFulfillmentResultLeftProcessingResponse.builder()
                .fulfillmentId(request.getFulfillmentId())
                .attemptId(request.getAttemptId())
                .status("PROCESSING")
                .processingStartedAt(toOffset(context.fulfillment().getProcessingStartedAt()))
                .lastObservedAt(request.getObservedAt())
                .build();
    }

    private void validateClaimRequest(ClaimFulfillmentForProcessingRequest request) {
        if (!EXECUTION_PATH_DEFAULT_PROVIDER.equals(request.getExecutionPath())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        requireDiagnosticTrace(request.getDiagnosticTrace());
    }

    private DiagnosticTrace requireDiagnosticTrace(DiagnosticTrace diagnosticTrace) {
        if (diagnosticTrace == null || diagnosticTrace.getTraceId() == null || diagnosticTrace.getDecision() == null
                || diagnosticTrace.getObservedAt() == null || diagnosticTrace.getTags() == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        return diagnosticTrace;
    }

    private DeliveryResult requireDeliveryResult(DeliveryResult deliveryResult) {
        if (deliveryResult == null || isBlank(deliveryResult.getResourceType()) || isBlank(deliveryResult.getResourceId())
                || deliveryResult.getPayloadSummary() == null || deliveryResult.getDeliveredAt() == null) {
            throw new BusinessException(ErrorCode.DELIVERY_RESULT_REQUIRED);
        }
        return deliveryResult;
    }

    private FailureSummary requireFailureSummary(FailureSummary failureSummary) {
        if (failureSummary == null || isBlank(failureSummary.getReasonCode()) || isBlank(failureSummary.getReasonMessage())
                || failureSummary.getFailedAt() == null) {
            throw new BusinessException(ErrorCode.FAILURE_SUMMARY_REQUIRED);
        }
        return failureSummary;
    }

    private void ensureClaimableState(FulfillmentRecord fulfillment) {
        if (!"PENDING".equals(fulfillment.getStatus())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_NOT_CLAIMABLE);
        }
        if (fulfillment.getCurrentAttemptId() != null || fulfillment.getTerminalAt() != null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
    }

    private BusinessException resolveClaimFailure(String fulfillmentId, long expectedVersion) {
        FulfillmentRecord latest = fulfillmentRecordMapper.selectById(fulfillmentId);
        if (latest == null) {
            return new BusinessException(ErrorCode.FULFILLMENT_NOT_FOUND);
        }
        if ("PROCESSING".equals(latest.getStatus())) {
            return new BusinessException(ErrorCode.FULFILLMENT_CLAIM_CONFLICT);
        }
        if (!"PENDING".equals(latest.getStatus())) {
            return new BusinessException(ErrorCode.FULFILLMENT_NOT_CLAIMABLE);
        }
        // Worker Decision: 并发 claim 输家在状态尚未刷新前也可能只体现为 version 漂移，这里显式归类为冲突而不是幽灵 409。
        if (!Objects.equals(latest.getVersion(), expectedVersion)) {
            return new BusinessException(ErrorCode.FULFILLMENT_CLAIM_CONFLICT);
        }
        return new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    private FulfillmentStateContext loadProcessingContext(String fulfillmentId, String attemptId) {
        FulfillmentRecord fulfillment = requireFulfillment(fulfillmentId);
        if ("SUCCEEDED".equals(fulfillment.getStatus()) || "FAILED".equals(fulfillment.getStatus())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_ALREADY_TERMINAL);
        }
        if (!"PROCESSING".equals(fulfillment.getStatus())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        if (!Objects.equals(fulfillment.getCurrentAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.FULFILLMENT_ATTEMPT_MISMATCH);
        }
        if (fulfillment.getProcessingStartedAt() == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        FulfillmentAttemptRecord attempt = fulfillmentAttemptRecordMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(attempt.getFulfillmentId(), fulfillmentId)) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        if (!"EXECUTING".equals(attempt.getStatus())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        return new FulfillmentStateContext(fulfillment, attempt);
    }

    private BusinessException resolveMutationFailure(String fulfillmentId, String attemptId) {
        FulfillmentRecord latest = fulfillmentRecordMapper.selectById(fulfillmentId);
        if (latest == null) {
            return new BusinessException(ErrorCode.FULFILLMENT_NOT_FOUND);
        }
        if ("SUCCEEDED".equals(latest.getStatus()) || "FAILED".equals(latest.getStatus())) {
            return new BusinessException(ErrorCode.FULFILLMENT_ALREADY_TERMINAL);
        }
        if (!Objects.equals(latest.getCurrentAttemptId(), attemptId)) {
            return new BusinessException(ErrorCode.FULFILLMENT_ATTEMPT_MISMATCH);
        }
        return new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    private FulfillmentRecord requireFulfillment(String fulfillmentId) {
        FulfillmentRecord fulfillment = fulfillmentRecordMapper.selectById(fulfillmentId);
        if (fulfillment == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_NOT_FOUND);
        }
        return fulfillment;
    }

    private void appendDispatchCandidateAudit(DispatchableFulfillmentCandidate candidate,
                                              ListDispatchableFulfillmentsRequest request,
                                              LocalDateTime scanObservedAt) {
        TicketOrder order = requireOrder(candidate.getOrderId());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_DISPATCH_CANDIDATE_FOUND");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(candidate.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(candidate.getOrderId());
        event.setFulfillmentId(candidate.getFulfillmentId());
        event.setActorType("SYSTEM");
        event.setActorRef(request.getScanId());
        event.setReasonCode("DISPATCH_SCAN_MATCHED");
        event.setPayloadSummaryJson(toJson(buildDispatchCandidatePayload(request, candidate)));
        event.setOccurredAt(scanObservedAt);
        auditTrailService.append(event);
    }

    private void appendProcessingStartedAudit(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt,
                                              DiagnosticTrace diagnosticTrace, LocalDateTime occurredAt) {
        TicketOrder order = requireOrder(fulfillment.getOrderId());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_PROCESSING_STARTED");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(fulfillment.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(fulfillment.getOrderId());
        event.setFulfillmentId(fulfillment.getFulfillmentId());
        event.setActorType("SYSTEM");
        event.setActorRef(attempt.getExecutorRef());
        event.setReasonCode("PROCESSING_CLAIMED");
        event.setPayloadSummaryJson(toJson(buildProcessingStartedPayload(attempt, diagnosticTrace)));
        event.setOccurredAt(occurredAt);
        auditTrailService.append(event);
    }

    private void appendLeftProcessingAudit(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt,
                                           DiagnosticTrace diagnosticTrace, LocalDateTime occurredAt) {
        TicketOrder order = requireOrder(fulfillment.getOrderId());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_RESULT_LEFT_PROCESSING");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(fulfillment.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(fulfillment.getOrderId());
        event.setFulfillmentId(fulfillment.getFulfillmentId());
        event.setActorType("SYSTEM");
        event.setActorRef(attempt.getExecutorRef());
        event.setReasonCode("RESULT_UNCERTAIN_LEFT_PROCESSING");
        event.setPayloadSummaryJson(toJson(buildLeftProcessingPayload(attempt, diagnosticTrace)));
        event.setOccurredAt(occurredAt);
        auditTrailService.append(event);
    }

    private void appendSucceededAudit(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt,
                                      DeliveryResult deliveryResult, DiagnosticTrace diagnosticTrace,
                                      LocalDateTime occurredAt) {
        TicketOrder order = requireOrder(fulfillment.getOrderId());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_SUCCEEDED");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(fulfillment.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(fulfillment.getOrderId());
        event.setFulfillmentId(fulfillment.getFulfillmentId());
        event.setActorType("SYSTEM");
        event.setActorRef(attempt.getExecutorRef());
        event.setReasonCode("DELIVERY_COMPLETED");
        event.setPayloadSummaryJson(toJson(buildSucceededPayload(attempt, deliveryResult, diagnosticTrace)));
        event.setOccurredAt(occurredAt);
        auditTrailService.append(event);
    }

    private void appendFailedAudit(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt,
                                   FailureSummary failureSummary, DiagnosticTrace diagnosticTrace,
                                   LocalDateTime occurredAt) {
        TicketOrder order = requireOrder(fulfillment.getOrderId());
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_FAILED");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(fulfillment.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(fulfillment.getOrderId());
        event.setFulfillmentId(fulfillment.getFulfillmentId());
        event.setActorType("SYSTEM");
        event.setActorRef(attempt.getExecutorRef());
        event.setReasonCode("DELIVERY_FAILED");
        event.setPayloadSummaryJson(toJson(buildFailedPayload(attempt, failureSummary, diagnosticTrace)));
        event.setOccurredAt(occurredAt);
        auditTrailService.append(event);
    }

    private TicketOrder requireOrder(String orderId) {
        if (ticketOrderMapper == null) {
            throw new IllegalStateException("ticketOrderMapper is required");
        }
        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        return order;
    }

    private DispatchableFulfillmentCandidate toDispatchableCandidate(FulfillmentRecord record) {
        return DispatchableFulfillmentCandidate.builder()
                .fulfillmentId(record.getFulfillmentId())
                .orderId(record.getOrderId())
                .status(record.getStatus())
                .createdAt(toOffset(record.getCreatedAt()))
                .version(record.getVersion())
                .build();
    }

    private Fulfillment toFulfillmentDto(FulfillmentRecord record) {
        return Fulfillment.builder()
                .fulfillmentId(record.getFulfillmentId())
                .orderId(record.getOrderId())
                .status(record.getStatus())
                .currentAttemptId(record.getCurrentAttemptId())
                .processingStartedAt(toOffset(record.getProcessingStartedAt()))
                .terminalAt(toOffset(record.getTerminalAt()))
                .executionPath(record.getExecutionPath())
                .deliveryResult(readJson(record.getDeliveryResultJson(), DeliveryResult.class))
                .lastFailure(readJson(record.getLastFailureJson(), FailureSummary.class))
                .lastDiagnosticTrace(readRequiredDiagnosticTrace(record.getLastDiagnosticTraceJson()))
                .version(record.getVersion())
                .createdAt(toOffset(record.getCreatedAt()))
                .updatedAt(toOffset(record.getUpdatedAt()))
                .build();
    }

    private FulfillmentAttempt toFulfillmentAttemptDto(FulfillmentAttemptRecord record) {
        return FulfillmentAttempt.builder()
                .attemptId(record.getAttemptId())
                .fulfillmentId(record.getFulfillmentId())
                .attemptNo(record.getAttemptNo())
                .status(record.getStatus())
                .dispatcherRunId(record.getDispatcherRunId())
                .executorRef(record.getExecutorRef())
                .executionPath(record.getExecutionPath())
                .claimedAt(toOffset(record.getClaimedAt()))
                .startedAt(toOffset(record.getStartedAt()))
                .finishedAt(toOffset(record.getFinishedAt()))
                .deliveryResult(readJson(record.getDeliveryResultJson(), DeliveryResult.class))
                .failure(readJson(record.getFailureJson(), FailureSummary.class))
                .diagnosticTrace(readJson(record.getDiagnosticTraceJson(), DiagnosticTrace.class))
                .build();
    }

    private Map<String, Object> buildDispatchCandidatePayload(ListDispatchableFulfillmentsRequest request,
                                                             DispatchableFulfillmentCandidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scanId", request.getScanId());
        payload.put("decision", "DISPATCH_CANDIDATE_FOUND");
        payload.put("cursor", request.getCursor());
        payload.put("batchSize", request.getBatchSize());
        payload.put("orderedBy", request.getOrderedBy());
        payload.put("version", candidate.getVersion());
        return payload;
    }

    private Map<String, Object> buildProcessingStartedPayload(FulfillmentAttemptRecord attempt, DiagnosticTrace diagnosticTrace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attempt.getAttemptId());
        payload.put("attemptNo", attempt.getAttemptNo());
        payload.put("dispatcherRunId", attempt.getDispatcherRunId());
        payload.put("executorRef", attempt.getExecutorRef());
        payload.put("executionPath", attempt.getExecutionPath());
        payload.put("decision", diagnosticTrace.getDecision());
        return payload;
    }

    private Map<String, Object> buildLeftProcessingPayload(FulfillmentAttemptRecord attempt, DiagnosticTrace diagnosticTrace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attempt.getAttemptId());
        payload.put("decision", diagnosticTrace.getDecision());
        payload.put("externalCallRef", diagnosticTrace.getExternalCallRef());
        payload.put("providerCorrelationKey", diagnosticTrace.getProviderCorrelationKey());
        return payload;
    }

    private Map<String, Object> buildSucceededPayload(FulfillmentAttemptRecord attempt, DeliveryResult deliveryResult,
                                                      DiagnosticTrace diagnosticTrace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attempt.getAttemptId());
        payload.put("decision", diagnosticTrace.getDecision());
        payload.put("resourceType", deliveryResult.getResourceType());
        payload.put("resourceId", deliveryResult.getResourceId());
        return payload;
    }

    private Map<String, Object> buildFailedPayload(FulfillmentAttemptRecord attempt, FailureSummary failureSummary,
                                                   DiagnosticTrace diagnosticTrace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", attempt.getAttemptId());
        payload.put("decision", diagnosticTrace.getDecision());
        payload.put("reasonCode", failureSummary.getReasonCode());
        payload.put("reasonMessage", failureSummary.getReasonMessage());
        return payload;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize fulfillment payload", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            log.error("Failed to deserialize fulfillment payload: type={}", type.getSimpleName(), e);
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }

    private DiagnosticTrace readRequiredDiagnosticTrace(String json) {
        return requireDiagnosticTrace(readJson(json, DiagnosticTrace.class));
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private void requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
    }

    private String encodeCursor(FulfillmentRecord record) {
        // Worker Decision: dispatch 分页采用 created_at + fulfillment_id 复合 cursor，避免同一时间戳下只靠 created_at 产生漏扫或重复。
        String raw = record.getCreatedAt().atOffset(ZoneOffset.UTC) + "|" + record.getFulfillmentId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            return new Cursor(OffsetDateTime.parse(parts[0]).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), parts[1]);
        } catch (IllegalArgumentException | DateTimeParseException | ArrayIndexOutOfBoundsException e) {
            // Worker Decision: cursor 是纯内部分页令牌；在无法安全解析时直接 fail fast，避免静默回退导致重复扫描范围失真。
            throw new IllegalArgumentException("cursor is invalid", e);
        }
    }

    private record Cursor(LocalDateTime createdAt, String fulfillmentId) {
    }

    private record FulfillmentStateContext(FulfillmentRecord fulfillment, FulfillmentAttemptRecord attempt) {
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
