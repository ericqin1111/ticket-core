package com.ticket.core.fulfillment.service;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FulfillmentGovernanceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void classifyAttemptFailure_retryablePath_movesToRetryPending_andStoresDecision() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        when(idempotencyService.hashRequest(any())).thenReturn("hash-1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-1"), eq("hash-1"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment("RETRY_PENDING"));
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt, reloadedAttempt("FAILED_CLASSIFIED", "NETWORK_TIMEOUT"));
        when(attemptMapper.updateById(any(FulfillmentAttemptRecord.class))).thenReturn(1);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-1");
        command.setExpectedVersion(7L);
        ObservedFailure observedFailure = new ObservedFailure();
        observedFailure.setFailureSignal("NETWORK_TIMEOUT");
        observedFailure.setProviderCode("504");
        observedFailure.setProviderMessage("gateway timeout");
        observedFailure.setRawOutcomeKnown(false);
        command.setObservedFailure(observedFailure);

        ClassifyAttemptFailureResult result = service.classifyAttemptFailure(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("RETRY_PENDING");
        assertThat(result.getDecision().getCategory()).isEqualTo("RETRYABLE_TECHNICAL_FAILURE");
        assertThat(result.getDecision().getRetryDisposition()).isEqualTo("ALLOW_FAST_RETRY");
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("ATTEMPT_CLASSIFIED", "MOVED_TO_RETRY_PENDING");
        verify(idempotencyService).markSucceeded(eq("idem-record-1"), eq("FULFILLMENT"), eq("ful-001"), any(ClassifyAttemptFailureResult.class));
    }

    @Test
    void scheduleRetryAfterFailure_whenBudgetExhausted_movesToManualPending() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = retryPendingFulfillment(exhaustedRetryState(), latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentRecord reloadedFulfillment = reloadedFulfillment("MANUAL_PENDING");
        reloadedFulfillment.setRetryStateJson(objectMapper.writeValueAsString(exhaustedRetryState()));
        when(idempotencyService.hashRequest(any())).thenReturn("hash-2");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-2"), eq("hash-2"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-2");
        command.setExpectedVersion(7L);
        command.setRequestedMode("BACKOFF_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        ScheduleRetryAfterFailureResult result = service.scheduleRetryAfterFailure(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(result.getScheduledAttemptTrigger()).isNull();
        assertThat(result.getNextRetryAt()).isNull();
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("MOVED_TO_MANUAL_PENDING");
        assertThat(result.getFulfillment().getRetryState().getBudgetExhausted()).isTrue();
        verify(idempotencyService).markSucceeded(eq("idem-record-1"), eq("FULFILLMENT"), eq("ful-001"), any(ScheduleRetryAfterFailureResult.class));
    }

    @Test
    void scheduleRetryAfterFailure_whenBackoffLimitExhausted_movesToManualPending() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(2)
                .totalRetryUsed(2)
                .nextRetryAt(null)
                .budgetExhausted(false)
                .build();
        FulfillmentRecord fulfillment = retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        RetryState reloadedState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(2)
                .totalRetryUsed(2)
                .nextRetryAt(null)
                .budgetExhausted(true)
                .build();
        FulfillmentRecord reloadedFulfillment = reloadedFulfillment("MANUAL_PENDING");
        reloadedFulfillment.setRetryStateJson(objectMapper.writeValueAsString(reloadedState));
        when(idempotencyService.hashRequest(any())).thenReturn("hash-2b");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-2b"), eq("hash-2b"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-2b");
        command.setExpectedVersion(7L);
        command.setRequestedMode("BACKOFF_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        ScheduleRetryAfterFailureResult result = service.scheduleRetryAfterFailure(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(result.getScheduledAttemptTrigger()).isNull();
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("MOVED_TO_MANUAL_PENDING");
        assertThat(result.getFulfillment().getRetryState().getBudgetExhausted()).isTrue();
    }

    @Test
    void startRetryAttempt_backoffDueReached_createsNewAttemptAndReturnsProcessing() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(1)
                .totalRetryUsed(2)
                .nextRetryAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"))
                .budgetExhausted(false)
                .build();
        FulfillmentRecord fulfillment = retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentAttemptRecord historicalAttempt = startedAttempt("attempt-old", 1, "INITIAL_EXECUTION");
        historicalAttempt.setExecutionStatus("FAILED_CLASSIFIED");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-3");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-3"), eq("hash-3"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment("PROCESSING"));
        when(attemptMapper.selectByFulfillmentId("ful-001")).thenReturn(List.of(historicalAttempt));
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);
        when(attemptMapper.selectById(anyString())).thenAnswer(invocation -> startedAttempt(invocation.getArgument(0), 2, "BACKOFF_RETRY"));

        StartRetryAttemptCommand command = new StartRetryAttemptCommand();
        command.setFulfillmentId("ful-001");
        command.setTrigger("BACKOFF_RETRY");
        command.setIdempotencyKey("idem-3");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        StartRetryAttemptResult result = service.startRetryAttempt(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("PROCESSING");
        assertThat(result.getAttempt().getSequenceNo()).isEqualTo(2);
        assertThat(result.getAttempt().getTrigger()).isEqualTo("BACKOFF_RETRY");
        ArgumentCaptor<FulfillmentAttemptRecord> attemptCaptor = ArgumentCaptor.forClass(FulfillmentAttemptRecord.class);
        verify(attemptMapper).insert(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getAttemptNo()).isEqualTo(2);
    }

    @Test
    void startRetryAttempt_withoutLatestFailure_stillStartsAttemptAndDoesNotExposeFailureDecisionRequired() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(1)
                .totalRetryUsed(2)
                .nextRetryAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"))
                .budgetExhausted(false)
                .build();
        FulfillmentRecord fulfillment = retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        FulfillmentRecord reloaded = reloadedFulfillment("PROCESSING");
        reloaded.setLatestFailureDecisionJson(null);
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentAttemptRecord historicalAttempt = startedAttempt("attempt-old", 1, "INITIAL_EXECUTION");
        historicalAttempt.setExecutionStatus("FAILED_CLASSIFIED");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-3b");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-3b"), eq("hash-3b"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloaded, reloaded);
        when(attemptMapper.selectByFulfillmentId("ful-001")).thenReturn(List.of(historicalAttempt));
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);
        when(attemptMapper.selectById(anyString())).thenAnswer(invocation -> startedAttempt(invocation.getArgument(0), 2, "BACKOFF_RETRY"));

        StartRetryAttemptCommand command = new StartRetryAttemptCommand();
        command.setFulfillmentId("ful-001");
        command.setTrigger("BACKOFF_RETRY");
        command.setIdempotencyKey("idem-3b");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        StartRetryAttemptResult result = service.startRetryAttempt(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("PROCESSING");
        assertThat(result.getAttempt().getSequenceNo()).isEqualTo(2);
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getReasonCode)
                .containsExactly((String) null);
    }

    @Test
    void governProcessingTimeout_withoutSafeEvidence_movesToManualPending_andAbandonsAttempt() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T10:00:00"));
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-4");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-4"), eq("hash-4"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment("MANUAL_PENDING"));
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt, reloadedAttempt("ABANDONED", "EXTERNAL_RESULT_UNKNOWN"));
        when(attemptMapper.updateById(any(FulfillmentAttemptRecord.class))).thenReturn(1);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        GovernProcessingTimeoutCommand command = new GovernProcessingTimeoutCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-4");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));
        SafeToRetryEvidence evidence = new SafeToRetryEvidence();
        evidence.setConfirmedNotSucceeded(false);
        evidence.setDuplicateExecutionRiskControllable(false);
        command.setSafeToRetryEvidence(evidence);

        GovernProcessingTimeoutResult result = service.governProcessingTimeout(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(result.getAttempt().getExecutionStatus()).isEqualTo("ABANDONED");
        assertThat(result.getDecision().getCategory()).isEqualTo("UNCERTAIN_RESULT");
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("ATTEMPT_ABANDONED", "ATTEMPT_CLASSIFIED", "PROCESSING_TIMEOUT_GOVERNED", "MOVED_TO_MANUAL_PENDING");
    }

    @Test
    void governProcessingTimeout_withSafeEvidence_movesToRetryPending_andKeepsRetryDecision() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T10:00:00"));
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "BACKOFF_RETRY");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-4b");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-4b"), eq("hash-4b"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment("RETRY_PENDING"));
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt, reloadedAttempt("ABANDONED", "PROCESSING_STUCK_SAFE_TO_RETRY"));
        when(attemptMapper.updateById(any(FulfillmentAttemptRecord.class))).thenReturn(1);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        GovernProcessingTimeoutCommand command = new GovernProcessingTimeoutCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-4b");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));
        SafeToRetryEvidence evidence = new SafeToRetryEvidence();
        evidence.setConfirmedNotSucceeded(true);
        evidence.setDuplicateExecutionRiskControllable(true);
        command.setSafeToRetryEvidence(evidence);

        GovernProcessingTimeoutResult result = service.governProcessingTimeout(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("RETRY_PENDING");
        assertThat(result.getDecision().getCategory()).isEqualTo("RETRYABLE_TECHNICAL_FAILURE");
        assertThat(result.getDecision().getReasonCode()).isEqualTo("PROCESSING_STUCK_SAFE_TO_RETRY");
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("ATTEMPT_ABANDONED", "ATTEMPT_CLASSIFIED", "PROCESSING_TIMEOUT_GOVERNED", "MOVED_TO_RETRY_PENDING");
    }

    @Test
    void recordAttemptSuccess_onFinalizedAttempt_throwsAttemptAlreadyFinalized() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        attempt.setExecutionStatus("FAILED_CLASSIFIED");
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        when(idempotencyService.hashRequest(any())).thenReturn("hash-5");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-5"), eq("hash-5"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);

        RecordAttemptSuccessCommand command = new RecordAttemptSuccessCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-5");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertThatThrownBy(() -> service.recordAttemptSuccess(command))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ATTEMPT_ALREADY_FINALIZED);
    }

    @Test
    void recordAttemptSuccess_withoutPriorFailure_movesToSucceeded() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setLatestFailureDecisionJson(null);
        IdempotencyRecord idempotencyRecord = newIdempotencyRecord();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-5b");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-5b"), eq("hash-5b"), eq("ful-001")))
                .thenReturn(idempotencyRecord);
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedSucceededFulfillmentWithoutFailure());
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt, succeededAttempt("attempt-001", 1, "INITIAL_EXECUTION"));
        when(attemptMapper.updateById(any(FulfillmentAttemptRecord.class))).thenReturn(1);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        RecordAttemptSuccessCommand command = new RecordAttemptSuccessCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-5b");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        RecordAttemptSuccessResult result = service.recordAttemptSuccess(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getFulfillment().getLatestFailure()).isNull();
        assertThat(result.getAttempt().getExecutionStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("MOVED_TO_SUCCEEDED");
    }

    @Test
    void getFulfillmentGovernanceView_returnsLatestAttemptLatestFailureAndRecentAudits() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = retryPendingFulfillment(activeRetryState(), latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        FulfillmentAttemptRecord latestAttempt = startedAttempt("attempt-002", 2, "BACKOFF_RETRY");
        FulfillmentGovernanceAuditRecordEntity auditRecord = new FulfillmentGovernanceAuditRecordEntity();
        auditRecord.setAuditId("audit-1");
        auditRecord.setFulfillmentId("ful-001");
        auditRecord.setAttemptId("attempt-002");
        auditRecord.setActionType("BACKOFF_RETRY_SCHEDULED");
        auditRecord.setFromStatus("RETRY_PENDING");
        auditRecord.setToStatus("RETRY_PENDING");
        auditRecord.setFailureCategory("RETRYABLE_TECHNICAL_FAILURE");
        auditRecord.setReasonCode("NETWORK_TIMEOUT");
        auditRecord.setRetryBudgetSnapshotJson(objectMapper.writeValueAsString(activeRetryState()));
        auditRecord.setActorType("SYSTEM");
        auditRecord.setOccurredAt(java.time.LocalDateTime.parse("2026-04-24T10:15:00"));
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-001")).thenReturn(latestAttempt);
        when(auditMapper.selectRecentByFulfillmentId("ful-001", 20)).thenReturn(List.of(auditRecord));

        GetFulfillmentGovernanceViewQuery query = new GetFulfillmentGovernanceViewQuery();
        query.setFulfillmentId("ful-001");

        GetFulfillmentGovernanceViewResult result = service.getFulfillmentGovernanceView(query);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("RETRY_PENDING");
        assertThat(result.getLatestAttempt().getAttemptId()).isEqualTo("attempt-002");
        assertThat(result.getLatestFailure().getReasonCode()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(result.getRecentAuditRecords()).hasSize(1);
    }

    @Test
    void getFulfillmentGovernanceView_whenRetryStateJsonCorrupted_doesNotExposeInvalidStatusTransition() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = retryPendingFulfillment(activeRetryState(), latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        fulfillment.setRetryStateJson("{broken");
        FulfillmentGovernanceAuditRecordEntity auditRecord = new FulfillmentGovernanceAuditRecordEntity();
        auditRecord.setAuditId("audit-2");
        auditRecord.setFulfillmentId("ful-001");
        auditRecord.setActionType("BACKOFF_RETRY_SCHEDULED");
        auditRecord.setRetryBudgetSnapshotJson("{broken");
        auditRecord.setActorType("SYSTEM");
        auditRecord.setOccurredAt(java.time.LocalDateTime.parse("2026-04-24T10:15:00"));
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(auditMapper.selectRecentByFulfillmentId("ful-001", 20)).thenReturn(List.of(auditRecord));

        GetFulfillmentGovernanceViewQuery query = new GetFulfillmentGovernanceViewQuery();
        query.setFulfillmentId("ful-001");

        GetFulfillmentGovernanceViewResult result = service.getFulfillmentGovernanceView(query);

        assertThat(result.getRetryState().getFastRetryUsed()).isEqualTo(0);
        assertThat(result.getRetryState().getBackoffRetryUsed()).isEqualTo(0);
        assertThat(result.getRetryState().getTotalRetryUsed()).isEqualTo(0);
        assertThat(result.getRetryState().getBudgetExhausted()).isFalse();
        assertThat(result.getFulfillment().getRetryState().getTotalRetryUsed()).isEqualTo(0);
        assertThat(result.getRecentAuditRecords()).extracting(GovernanceAuditRecord::getRetryBudgetSnapshot)
                .containsExactly((RetryState) null);
    }

    @Test
    void classifyAttemptFailure_withoutFailureSignal_throwsFailureDecisionRequired() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-c1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c1"), eq("hash-c1"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-c1");
        command.setExpectedVersion(7L);
        command.setObservedFailure(new ObservedFailure());

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.FAILURE_DECISION_REQUIRED);
    }

    @Test
    void classifyAttemptFailure_whenFulfillmentMissing_throwsFulfillmentNotFound() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        when(idempotencyService.hashRequest(any())).thenReturn("hash-c1b");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c1b"), eq("hash-c1b"), eq("missing")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("missing")).thenReturn(null);

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("missing");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-c1b");
        command.setExpectedVersion(7L);
        ObservedFailure observedFailure = new ObservedFailure();
        observedFailure.setFailureSignal("NETWORK_TIMEOUT");
        command.setObservedFailure(observedFailure);

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.FULFILLMENT_NOT_FOUND);
    }

    @Test
    void classifyAttemptFailure_whenIdempotencyReplayStillProcessing_throwsReplayConflict() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        when(idempotencyService.hashRequest(any())).thenReturn("hash-c2");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c2"), eq("hash-c2"), eq("ful-001")))
                .thenReturn(existingIdempotencyRecord("PROCESSING"));

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-c2");
        command.setExpectedVersion(7L);
        command.setObservedFailure(new ObservedFailure());

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.IDEMPOTENT_REPLAY_CONFLICT);
    }

    @Test
    void classifyAttemptFailure_whenExpectedVersionMismatch_throwsConcurrencyVersionMismatch() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        when(idempotencyService.hashRequest(any())).thenReturn("hash-c3");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c3"), eq("hash-c3"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processingFulfillment());

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-c3");
        command.setExpectedVersion(8L);
        ObservedFailure observedFailure = new ObservedFailure();
        observedFailure.setFailureSignal("NETWORK_TIMEOUT");
        command.setObservedFailure(observedFailure);

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.CONCURRENCY_VERSION_MISMATCH);
    }

    @Test
    void classifyAttemptFailure_whenAttemptDoesNotOwnExecution_throwsInvalidStatusTransition() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord anotherAttempt = startedAttempt("attempt-999", 2, "FAST_RETRY");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-c4");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c4"), eq("hash-c4"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-999")).thenReturn(anotherAttempt);

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-999");
        command.setIdempotencyKey("idem-c4");
        command.setExpectedVersion(7L);
        ObservedFailure observedFailure = new ObservedFailure();
        observedFailure.setFailureSignal("NETWORK_TIMEOUT");
        command.setObservedFailure(observedFailure);

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void classifyAttemptFailure_whenAttemptAlreadyFinalized_throwsAttemptAlreadyFinalized() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        attempt.setExecutionStatus("FAILED_CLASSIFIED");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-c5");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-c5"), eq("hash-c5"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);

        ClassifyAttemptFailureCommand command = new ClassifyAttemptFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-001");
        command.setIdempotencyKey("idem-c5");
        command.setExpectedVersion(7L);
        ObservedFailure observedFailure = new ObservedFailure();
        observedFailure.setFailureSignal("NETWORK_TIMEOUT");
        command.setObservedFailure(observedFailure);

        assertBusinessError(() -> service.classifyAttemptFailure(command), ErrorCode.ATTEMPT_ALREADY_FINALIZED);
    }

    @Test
    void scheduleRetryAfterFailure_whenLatestFailureIsManualReview_throwsFailureCategoryNotRetryable() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = retryPendingFulfillment(activeRetryState(), FailureDecision.builder()
                .category("MANUAL_REVIEW_REQUIRED")
                .reasonCode("UPSTREAM_DATA_REQUIRES_REVIEW")
                .retryDisposition("STOP_AND_MANUAL")
                .manualReviewRequired(true)
                .finalTerminationSuggested(false)
                .rationale("manual")
                .classifiedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"))
                .build());
        when(idempotencyService.hashRequest(any())).thenReturn("hash-s1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-s1"), eq("hash-s1"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-s1");
        command.setExpectedVersion(7L);
        command.setRequestedMode("FAST_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.scheduleRetryAfterFailure(command), ErrorCode.FAILURE_CATEGORY_NOT_RETRYABLE);
    }

    @Test
    void scheduleRetryAfterFailure_whenFastRetryAlreadyUsed_throwsFastRetryAlreadyUsed() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(0)
                .totalRetryUsed(1)
                .nextRetryAt(null)
                .budgetExhausted(false)
                .build();
        when(idempotencyService.hashRequest(any())).thenReturn("hash-s2");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-s2"), eq("hash-s2"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_FAST_RETRY")));

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-s2");
        command.setExpectedVersion(7L);
        command.setRequestedMode("FAST_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.scheduleRetryAfterFailure(command), ErrorCode.FAST_RETRY_ALREADY_USED);
    }

    @Test
    void scheduleRetryAfterFailure_whenBackoffNotDue_throwsNextRetryNotDue() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(0)
                .totalRetryUsed(1)
                .nextRetryAt(OffsetDateTime.parse("2026-04-24T10:35:00Z"))
                .budgetExhausted(false)
                .build();
        when(idempotencyService.hashRequest(any())).thenReturn("hash-s3");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-s3"), eq("hash-s3"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_BACKOFF_RETRY")));

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-s3");
        command.setExpectedVersion(7L);
        command.setRequestedMode("BACKOFF_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.scheduleRetryAfterFailure(command), ErrorCode.NEXT_RETRY_NOT_DUE);
    }

    @Test
    void scheduleRetryAfterFailure_whenBackoffScheduleMissing_throwsInvalidStatusTransition() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = retryPendingFulfillment(activeRetryState(), latestRetryableFailure("ALLOW_BACKOFF_RETRY"));
        fulfillment.setRetryPolicyJson(objectMapper.writeValueAsString(RetryPolicySnapshot.builder()
                .fastRetryLimit(1)
                .backoffRetryLimit(2)
                .totalRetryBudget(3)
                .backoffSchedule(List.of())
                .build()));
        when(idempotencyService.hashRequest(any())).thenReturn("hash-s4");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-s4"), eq("hash-s4"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);

        ScheduleRetryAfterFailureCommand command = new ScheduleRetryAfterFailureCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-s4");
        command.setExpectedVersion(7L);
        command.setRequestedMode("BACKOFF_RETRY");
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.scheduleRetryAfterFailure(command), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void startRetryAttempt_whenBackoffNotDue_throwsNextRetryNotDue() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        RetryState retryState = RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(1)
                .totalRetryUsed(2)
                .nextRetryAt(OffsetDateTime.parse("2026-04-24T10:35:00Z"))
                .budgetExhausted(false)
                .build();
        when(idempotencyService.hashRequest(any())).thenReturn("hash-r1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-r1"), eq("hash-r1"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(retryPendingFulfillment(retryState, latestRetryableFailure("ALLOW_BACKOFF_RETRY")));

        StartRetryAttemptCommand command = new StartRetryAttemptCommand();
        command.setFulfillmentId("ful-001");
        command.setTrigger("BACKOFF_RETRY");
        command.setIdempotencyKey("idem-r1");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.startRetryAttempt(command), ErrorCode.NEXT_RETRY_NOT_DUE);
    }

    @Test
    void governProcessingTimeout_beforeLeaseExpires_throwsProcessingNotTimedOut() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T10:45:00"));
        when(idempotencyService.hashRequest(any())).thenReturn("hash-g1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-g1"), eq("hash-g1"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);

        GovernProcessingTimeoutCommand command = new GovernProcessingTimeoutCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-g1");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.governProcessingTimeout(command), ErrorCode.PROCESSING_NOT_TIMED_OUT);
    }

    @Test
    void governProcessingTimeout_whenUnsafeToRetry_movesToManualPending_withUnsafeReasonCode() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T10:00:00"));
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-g2");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-g2"), eq("hash-g2"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment, reloadedFulfillment("MANUAL_PENDING"));
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt, reloadedAttempt("ABANDONED", "PROCESSING_STUCK_UNSAFE_TO_RETRY"));
        when(attemptMapper.updateById(any(FulfillmentAttemptRecord.class))).thenReturn(1);
        when(fulfillmentMapper.updateById(any(FulfillmentRecord.class))).thenReturn(1);

        GovernProcessingTimeoutCommand command = new GovernProcessingTimeoutCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-g2");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));
        SafeToRetryEvidence evidence = new SafeToRetryEvidence();
        evidence.setConfirmedNotSucceeded(true);
        evidence.setDuplicateExecutionRiskControllable(false);
        command.setSafeToRetryEvidence(evidence);

        GovernProcessingTimeoutResult result = service.governProcessingTimeout(command);

        assertThat(result.getFulfillment().getStatus()).isEqualTo("MANUAL_PENDING");
        assertThat(result.getAttempt().getExecutionStatus()).isEqualTo("ABANDONED");
        assertThat(result.getDecision().getCategory()).isEqualTo("UNCERTAIN_RESULT");
        assertThat(result.getDecision().getReasonCode()).isEqualTo("PROCESSING_STUCK_UNSAFE_TO_RETRY");
        assertThat(result.getEmittedAuditRecords()).extracting(GovernanceAuditRecord::getActionType)
                .containsExactly("ATTEMPT_ABANDONED", "ATTEMPT_CLASSIFIED", "PROCESSING_TIMEOUT_GOVERNED", "MOVED_TO_MANUAL_PENDING");
    }

    @Test
    void governProcessingTimeout_whenAttemptAlreadyFinalized_throwsInvalidStatusTransition() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        fulfillment.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T10:00:00"));
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        attempt.setExecutionStatus("FAILED_CLASSIFIED");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-g3");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-g3"), eq("hash-g3"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);

        GovernProcessingTimeoutCommand command = new GovernProcessingTimeoutCommand();
        command.setFulfillmentId("ful-001");
        command.setIdempotencyKey("idem-g3");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.governProcessingTimeout(command), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void recordAttemptSuccess_whenAttemptDoesNotOwnExecution_throwsInvalidStatusTransition() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = processingFulfillment();
        FulfillmentAttemptRecord anotherAttempt = startedAttempt("attempt-999", 2, "FAST_RETRY");
        when(idempotencyService.hashRequest(any())).thenReturn("hash-a1");
        when(idempotencyService.checkAndMarkProcessing(anyString(), eq("idem-a1"), eq("hash-a1"), eq("ful-001")))
                .thenReturn(newIdempotencyRecord());
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(attemptMapper.selectById("attempt-999")).thenReturn(anotherAttempt);

        RecordAttemptSuccessCommand command = new RecordAttemptSuccessCommand();
        command.setFulfillmentId("ful-001");
        command.setAttemptId("attempt-999");
        command.setIdempotencyKey("idem-a1");
        command.setExpectedVersion(7L);
        command.setNow(OffsetDateTime.parse("2026-04-24T10:30:00Z"));

        assertBusinessError(() -> service.recordAttemptSuccess(command), ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void getFulfillmentGovernanceView_whenFulfillmentMissing_throwsFulfillmentNotFound() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        when(fulfillmentMapper.selectById("missing")).thenReturn(null);

        GetFulfillmentGovernanceViewQuery query = new GetFulfillmentGovernanceViewQuery();
        query.setFulfillmentId("missing");

        assertBusinessError(() -> service.getFulfillmentGovernanceView(query), ErrorCode.FULFILLMENT_NOT_FOUND);
    }

    @Test
    void getFulfillmentGovernanceView_withoutLeaseTimestamps_returnsNullCurrentProcessingLease() throws Exception {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentGovernanceAuditRecordMapper auditMapper = mock(FulfillmentGovernanceAuditRecordMapper.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        FulfillmentGovernanceService service = new FulfillmentGovernanceService(
                fulfillmentMapper, attemptMapper, auditMapper, idempotencyService, objectMapper);

        FulfillmentRecord fulfillment = reloadedSucceededFulfillmentWithoutFailure();
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(fulfillment);
        when(auditMapper.selectRecentByFulfillmentId("ful-001", 20)).thenReturn(List.of());

        GetFulfillmentGovernanceViewQuery query = new GetFulfillmentGovernanceViewQuery();
        query.setFulfillmentId("ful-001");

        GetFulfillmentGovernanceViewResult result = service.getFulfillmentGovernanceView(query);

        assertThat(result.getFulfillment().getCurrentProcessingLease()).isNull();
    }

    private IdempotencyRecord newIdempotencyRecord() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId("idem-record-1");
        record.setStatus("PROCESSING");
        record.setNewlyCreated(true);
        return record;
    }

    private IdempotencyRecord existingIdempotencyRecord(String status) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId("idem-record-existing");
        record.setStatus(status);
        record.setNewlyCreated(false);
        return record;
    }

    private void assertBusinessError(ThrowingCallable callable, ErrorCode expectedErrorCode) {
        assertThatThrownBy(callable::call)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(expectedErrorCode);
    }

    private FulfillmentRecord processingFulfillment() throws Exception {
        FulfillmentRecord record = baseFulfillment("PROCESSING");
        record.setCurrentAttemptId("attempt-001");
        record.setLatestAttemptId("attempt-001");
        record.setProcessingStartedAt(java.time.LocalDateTime.parse("2026-04-24T09:30:00"));
        record.setProcessingTimeoutAt(java.time.LocalDateTime.parse("2026-04-24T09:45:00"));
        return record;
    }

    private FulfillmentRecord retryPendingFulfillment(RetryState retryState, FailureDecision latestFailure) throws Exception {
        FulfillmentRecord record = baseFulfillment("RETRY_PENDING");
        record.setLatestAttemptId("attempt-001");
        record.setLatestFailureDecisionJson(objectMapper.writeValueAsString(latestFailure));
        record.setRetryStateJson(objectMapper.writeValueAsString(retryState));
        return record;
    }

    private FulfillmentRecord reloadedFulfillment(String status) throws Exception {
        FulfillmentRecord record = baseFulfillment(status);
        record.setLatestAttemptId("attempt-001");
        record.setLatestFailureDecisionJson(objectMapper.writeValueAsString(latestRetryableFailure("ALLOW_FAST_RETRY")));
        return record;
    }

    private FulfillmentAttemptRecord startedAttempt(String attemptId, int attemptNo, String trigger) {
        FulfillmentAttemptRecord attempt = new FulfillmentAttemptRecord();
        attempt.setAttemptId(attemptId);
        attempt.setFulfillmentId("ful-001");
        attempt.setAttemptNo(attemptNo);
        attempt.setTrigger(trigger);
        attempt.setExecutionStatus("STARTED");
        attempt.setStatus("EXECUTING");
        attempt.setDispatcherRunId("run-1");
        attempt.setExecutorRef("worker-1");
        attempt.setExecutionPath("DEFAULT_PROVIDER");
        attempt.setClaimedAt(java.time.LocalDateTime.parse("2026-04-24T09:30:00"));
        attempt.setStartedAt(java.time.LocalDateTime.parse("2026-04-24T09:30:00"));
        return attempt;
    }

    private FulfillmentAttemptRecord succeededAttempt(String attemptId, int attemptNo, String trigger) {
        FulfillmentAttemptRecord attempt = startedAttempt(attemptId, attemptNo, trigger);
        attempt.setExecutionStatus("SUCCEEDED");
        attempt.setStatus("SUCCEEDED");
        attempt.setFinishedAt(java.time.LocalDateTime.parse("2026-04-24T10:30:00"));
        return attempt;
    }

    private FulfillmentAttemptRecord reloadedAttempt(String executionStatus, String reasonCode) throws Exception {
        FulfillmentAttemptRecord attempt = startedAttempt("attempt-001", 1, "INITIAL_EXECUTION");
        attempt.setExecutionStatus(executionStatus);
        attempt.setStatus("ABANDONED".equals(executionStatus) ? "ABANDONED" : "FAILED");
        attempt.setFailureDecisionJson(objectMapper.writeValueAsString(FailureDecision.builder()
                .category("ABANDONED".equals(executionStatus) ? "UNCERTAIN_RESULT" : "RETRYABLE_TECHNICAL_FAILURE")
                .reasonCode(reasonCode)
                .retryDisposition("STOP_AND_MANUAL")
                .manualReviewRequired(true)
                .finalTerminationSuggested(false)
                .rationale("reloaded")
                .classifiedAt(OffsetDateTime.parse("2026-04-24T10:30:00Z"))
                .build()));
        return attempt;
    }

    private FulfillmentRecord baseFulfillment(String status) throws Exception {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId("ful-001");
        record.setOrderId("order-001");
        record.setStatus(status);
        record.setExecutionPath("DEFAULT_PROVIDER");
        record.setVersion(7L);
        record.setCreatedAt(java.time.LocalDateTime.parse("2026-04-24T09:00:00"));
        record.setUpdatedAt(java.time.LocalDateTime.parse("2026-04-24T09:00:00"));
        record.setRetryPolicyJson(objectMapper.writeValueAsString(RetryPolicySnapshot.builder()
                .fastRetryLimit(1)
                .backoffRetryLimit(2)
                .totalRetryBudget(3)
                .backoffSchedule(List.of("PT5M", "PT15M"))
                .build()));
        record.setRetryStateJson(objectMapper.writeValueAsString(activeRetryState()));
        record.setLastDiagnosticTraceJson("""
                {"traceId":"trace-1","decision":"EXECUTION_STARTED","observedAt":"2026-04-24T09:30:00Z","tags":{"source":"worker"}}
                """);
        return record;
    }

    private FulfillmentRecord reloadedSucceededFulfillmentWithoutFailure() throws Exception {
        FulfillmentRecord record = baseFulfillment("SUCCEEDED");
        record.setLatestAttemptId("attempt-001");
        record.setCurrentAttemptId(null);
        record.setProcessingStartedAt(null);
        record.setProcessingTimeoutAt(null);
        record.setTerminalAt(java.time.LocalDateTime.parse("2026-04-24T10:30:00"));
        record.setLatestFailureDecisionJson(null);
        return record;
    }

    private RetryState activeRetryState() {
        return RetryState.builder()
                .fastRetryUsed(0)
                .backoffRetryUsed(0)
                .totalRetryUsed(0)
                .nextRetryAt(null)
                .budgetExhausted(false)
                .build();
    }

    private RetryState exhaustedRetryState() {
        return RetryState.builder()
                .fastRetryUsed(1)
                .backoffRetryUsed(2)
                .totalRetryUsed(3)
                .nextRetryAt(null)
                .budgetExhausted(true)
                .build();
    }

    private FailureDecision latestRetryableFailure(String disposition) {
        return FailureDecision.builder()
                .category("RETRYABLE_TECHNICAL_FAILURE")
                .reasonCode("NETWORK_TIMEOUT")
                .retryDisposition(disposition)
                .manualReviewRequired(false)
                .finalTerminationSuggested(false)
                .rationale("retryable")
                .classifiedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"))
                .build();
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
