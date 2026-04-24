package com.ticket.core.fulfillment.service;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FulfillmentServiceTest {

    @Test
    void findByOrderId_delegatesToMapperAndReturnsRecord() {
        FulfillmentRecord record = pendingFulfillment();
        AtomicReference<String> invokedMethod = new AtomicReference<>();
        FulfillmentRecordMapper mapper = mapperProxy(invokedMethod, record, null);
        FulfillmentService fulfillmentService = new FulfillmentService(mapper);

        FulfillmentRecord result = fulfillmentService.findByOrderId("order-001");

        assertThat(result).isSameAs(record);
        assertThat(invokedMethod.get()).isEqualTo("selectOne");
    }

    @Test
    void create_insertsProvidedFulfillmentRecord() {
        FulfillmentRecord record = pendingFulfillment();
        AtomicReference<FulfillmentRecord> insertedRecord = new AtomicReference<>();
        FulfillmentRecordMapper mapper = mapperProxy(new AtomicReference<>(), null, insertedRecord);
        FulfillmentService fulfillmentService = new FulfillmentService(mapper);

        fulfillmentService.create(record);

        assertThat(insertedRecord.get()).isNotNull();
        assertThat(insertedRecord.get().getFulfillmentId()).isEqualTo("ful-001");
        assertThat(insertedRecord.get().getOrderId()).isEqualTo("order-001");
        assertThat(insertedRecord.get().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void listDispatchableFulfillments_returnsPendingCandidatesAndAppendsAuditPerRow() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord first = pendingFulfillment();
        FulfillmentRecord second = pendingFulfillment();
        second.setFulfillmentId("ful-002");
        second.setOrderId("order-002");
        second.setCreatedAt(LocalDateTime.of(2026, 4, 24, 9, 5));
        when(fulfillmentMapper.selectDispatchableBatch(null, null, 2)).thenReturn(List.of(first, second));
        when(orderMapper.selectById("order-001")).thenReturn(order("order-001", "trade-001"));
        when(orderMapper.selectById("order-002")).thenReturn(order("order-002", "trade-002"));

        ListDispatchableFulfillmentsRequest request = new ListDispatchableFulfillmentsRequest();
        request.setScanId("scan-001");
        request.setBatchSize(2);
        request.setOrderedBy("created_at");

        LocalDateTime beforeCall = LocalDateTime.now(ZoneOffset.UTC);
        ListDispatchableFulfillmentsResponse response = service.listDispatchableFulfillments(request);
        LocalDateTime afterCall = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(response.getCandidates()).hasSize(2);
        assertThat(response.getCandidates()).extracting(DispatchableFulfillmentCandidate::getFulfillmentId)
                .containsExactly("ful-001", "ful-002");
        assertThat(response.getNextCursor()).isNotBlank();
        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService, times(2)).append(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).extracting(AuditTrailEvent::getEventType)
                .containsOnly("FULFILLMENT_DISPATCH_CANDIDATE_FOUND");
        assertThat(auditCaptor.getAllValues())
                .extracting(AuditTrailEvent::getOccurredAt)
                .allSatisfy(occurredAt -> {
                    assertThat(occurredAt).isBetween(beforeCall, afterCall);
                    assertThat(occurredAt).isNotEqualTo(first.getCreatedAt());
                });
    }

    @Test
    void getFulfillmentExecutionSnapshot_whenMissing_throwsNotFound() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, mock(TicketOrderMapper.class), new ObjectMapper(), mock(AuditTrailService.class));
        when(fulfillmentMapper.selectById("missing-001")).thenReturn(null);
        GetFulfillmentExecutionSnapshotRequest request = new GetFulfillmentExecutionSnapshotRequest();
        request.setFulfillmentId("missing-001");

        assertThatThrownBy(() -> service.getFulfillmentExecutionSnapshot(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_NOT_FOUND);
    }

    @Test
    void getFulfillmentExecutionSnapshot_whenLastDiagnosticTraceMissing_throwsInvariantBroken() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, mock(TicketOrderMapper.class), new ObjectMapper(), mock(AuditTrailService.class));
        FulfillmentRecord record = pendingFulfillment();
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(record);
        when(attemptMapper.selectByFulfillmentId("ful-001")).thenReturn(List.of());

        GetFulfillmentExecutionSnapshotRequest request = new GetFulfillmentExecutionSnapshotRequest();
        request.setFulfillmentId("ful-001");

        assertThatThrownBy(() -> service.getFulfillmentExecutionSnapshot(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    @Test
    void listStuckProcessingFulfillments_returnsItemsOlderThanThreshold() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, mock(TicketOrderMapper.class), new ObjectMapper(), mock(AuditTrailService.class));
        FulfillmentRecord processing = processingFulfillment("attempt-001");
        processing.setLastDiagnosticTraceJson("""
                {"traceId":"trace-001","decision":"RESULT_LEFT_PROCESSING","observedAt":"2026-04-24T09:00:00Z","tags":{"source":"worker"}}
                """);
        when(fulfillmentMapper.selectStuckProcessingBatch(any(LocalDateTime.class), eq(10))).thenReturn(List.of(processing));
        ListStuckProcessingFulfillmentsRequest request = new ListStuckProcessingFulfillmentsRequest();
        request.setOlderThan(Duration.ofMinutes(30));
        request.setBatchSize(10);

        ListStuckProcessingFulfillmentsResponse response = service.listStuckProcessingFulfillments(request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getDiagnosticTrace().getDecision()).isEqualTo("RESULT_LEFT_PROCESSING");
    }

    @Test
    void claimFulfillmentForProcessing_happyPath_createsAttemptClaimsStateAndAppendsAudit() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord pending = pendingFulfillment();
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(pending);
        when(fulfillmentMapper.claimForProcessing(eq("ful-001"), any(String.class), any(LocalDateTime.class),
                eq("DEFAULT_PROVIDER"), any(String.class), eq(0L))).thenReturn(1);
        when(orderMapper.selectById("order-001")).thenReturn(order("order-001", "trade-001"));

        ClaimFulfillmentForProcessingRequest request = new ClaimFulfillmentForProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setExpectedVersion(0L);
        request.setDispatcherRunId("scan-001");
        request.setExecutorRef("worker-A");
        request.setExecutionPath("DEFAULT_PROVIDER");
        request.setClaimedAt(OffsetDateTime.parse("2026-04-24T09:30:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-001", "CLAIM_ACCEPTED", "2026-04-24T09:30:00Z"));

        ClaimFulfillmentForProcessingResponse response = service.claimFulfillmentForProcessing(request);

        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        assertThat(response.getAttemptId()).isNotBlank();
        verify(attemptMapper).insert(any(FulfillmentAttemptRecord.class));
        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo("FULFILLMENT_PROCESSING_STARTED");
    }

    @Test
    void claimFulfillmentForProcessing_whenAlreadyProcessing_throwsNotClaimable() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, mock(TicketOrderMapper.class), new ObjectMapper(), mock(AuditTrailService.class));
        FulfillmentRecord processing = processingFulfillment("attempt-001");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processing);

        ClaimFulfillmentForProcessingRequest request = new ClaimFulfillmentForProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setExpectedVersion(1L);
        request.setDispatcherRunId("scan-001");
        request.setExecutorRef("worker-A");
        request.setExecutionPath("DEFAULT_PROVIDER");
        request.setClaimedAt(OffsetDateTime.parse("2026-04-24T09:30:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-001", "CLAIM_ACCEPTED", "2026-04-24T09:30:00Z"));

        assertThatThrownBy(() -> service.claimFulfillmentForProcessing(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_NOT_CLAIMABLE);
        verify(attemptMapper, never()).insert(any(FulfillmentAttemptRecord.class));
    }

    @Test
    void claimFulfillmentForProcessing_whenConcurrentWorkerClaimsFirst_throwsClaimConflict() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord pending = pendingFulfillment();
        FulfillmentRecord processing = processingFulfillment("attempt-other");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(pending, processing);
        when(fulfillmentMapper.claimForProcessing(eq("ful-001"), any(String.class), any(LocalDateTime.class),
                eq("DEFAULT_PROVIDER"), any(String.class), eq(0L))).thenReturn(0);

        ClaimFulfillmentForProcessingRequest request = new ClaimFulfillmentForProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setExpectedVersion(0L);
        request.setDispatcherRunId("scan-001");
        request.setExecutorRef("worker-A");
        request.setExecutionPath("DEFAULT_PROVIDER");
        request.setClaimedAt(OffsetDateTime.parse("2026-04-24T09:30:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-001", "CLAIM_ACCEPTED", "2026-04-24T09:30:00Z"));

        assertThatThrownBy(() -> service.claimFulfillmentForProcessing(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_CLAIM_CONFLICT);
        verify(attemptMapper).insert(any(FulfillmentAttemptRecord.class));
    }

    @Test
    void claimFulfillmentForProcessing_withoutDiagnosticTags_throwsInvariantBroken() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        ClaimFulfillmentForProcessingRequest request = new ClaimFulfillmentForProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setExpectedVersion(0L);
        request.setDispatcherRunId("scan-001");
        request.setExecutorRef("worker-A");
        request.setExecutionPath("DEFAULT_PROVIDER");
        request.setClaimedAt(OffsetDateTime.parse("2026-04-24T09:30:00Z"));
        request.setDiagnosticTrace(diagnosticTraceWithoutTags("trace-001", "CLAIM_ACCEPTED", "2026-04-24T09:30:00Z"));

        assertThatThrownBy(() -> service.claimFulfillmentForProcessing(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    @Test
    void markFulfillmentSucceeded_happyPath_updatesStateAndAppendsAudit() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord processing = processingFulfillment("attempt-001");
        FulfillmentAttemptRecord attempt = attempt("attempt-001");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processing);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);
        when(fulfillmentMapper.markSucceeded(eq("ful-001"), eq("attempt-001"), any(LocalDateTime.class),
                any(String.class), any(String.class), eq(1L))).thenReturn(1);
        when(attemptMapper.markSucceeded(eq("ful-001"), eq("attempt-001"), any(LocalDateTime.class),
                any(String.class), any(String.class))).thenReturn(1);
        when(orderMapper.selectById("order-001")).thenReturn(order("order-001", "trade-001"));

        MarkFulfillmentSucceededRequest request = new MarkFulfillmentSucceededRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setSucceededAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-002", "RESULT_COMMITTED", "2026-04-24T10:00:00Z"));
        DeliveryResult deliveryResult = new DeliveryResult();
        deliveryResult.setResourceType("TICKET");
        deliveryResult.setResourceId("ticket-001");
        deliveryResult.setPayloadSummary(Map.of("seat", "A-1"));
        deliveryResult.setDeliveredAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setDeliveryResult(deliveryResult);

        MarkFulfillmentSucceededResponse response = service.markFulfillmentSucceeded(request);

        assertThat(response.getStatus()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo("FULFILLMENT_SUCCEEDED");
    }

    @Test
    void markFulfillmentSucceeded_withoutDeliveryResult_throwsRequiredError() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        MarkFulfillmentSucceededRequest request = new MarkFulfillmentSucceededRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setDiagnosticTrace(diagnosticTrace("trace-002", "RESULT_COMMITTED", "2026-04-24T10:00:00Z"));
        request.setSucceededAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));

        assertThatThrownBy(() -> service.markFulfillmentSucceeded(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELIVERY_RESULT_REQUIRED);
    }

    @Test
    void markFulfillmentSucceeded_withIncompleteDeliveryResult_throwsRequiredError() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        MarkFulfillmentSucceededRequest request = succeededRequest();
        DeliveryResult deliveryResult = validDeliveryResult();
        deliveryResult.setPayloadSummary(null);
        request.setDeliveryResult(deliveryResult);

        assertThatThrownBy(() -> service.markFulfillmentSucceeded(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DELIVERY_RESULT_REQUIRED);
    }

    @Test
    void markFulfillmentSucceeded_withoutDiagnosticTags_throwsInvariantBroken() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        MarkFulfillmentSucceededRequest request = succeededRequest();
        request.setDiagnosticTrace(diagnosticTraceWithoutTags("trace-002", "RESULT_COMMITTED", "2026-04-24T10:00:00Z"));

        assertThatThrownBy(() -> service.markFulfillmentSucceeded(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    @Test
    void markFulfillmentFailed_happyPath_updatesStateAndAppendsAudit() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord processing = processingFulfillment("attempt-001");
        FulfillmentAttemptRecord attempt = attempt("attempt-001");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processing);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);
        when(fulfillmentMapper.markFailed(eq("ful-001"), eq("attempt-001"), any(LocalDateTime.class),
                any(String.class), any(String.class), eq(1L))).thenReturn(1);
        when(attemptMapper.markFailed(eq("ful-001"), eq("attempt-001"), any(LocalDateTime.class),
                any(String.class), any(String.class))).thenReturn(1);
        when(orderMapper.selectById("order-001")).thenReturn(order("order-001", "trade-001"));

        MarkFulfillmentFailedRequest request = failedRequest("attempt-001");

        MarkFulfillmentFailedResponse response = service.markFulfillmentFailed(request);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo("FULFILLMENT_FAILED");
    }

    @Test
    void markFulfillmentFailed_withIncompleteFailureSummary_throwsRequiredError() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        MarkFulfillmentFailedRequest request = failedRequest("attempt-001");
        FailureSummary failureSummary = validFailureSummary();
        failureSummary.setReasonMessage(" ");
        request.setFailure(failureSummary);

        assertThatThrownBy(() -> service.markFulfillmentFailed(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FAILURE_SUMMARY_REQUIRED);
    }

    @Test
    void markFulfillmentFailed_whenAttemptMismatch_throwsBusinessError() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, mock(TicketOrderMapper.class), new ObjectMapper(), mock(AuditTrailService.class));
        FulfillmentRecord processing = processingFulfillment("attempt-current");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processing);

        MarkFulfillmentFailedRequest request = new MarkFulfillmentFailedRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-stale");
        request.setExpectedVersion(1L);
        request.setFailedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-003", "PROVIDER_CALL_FAILED", "2026-04-24T10:00:00Z"));
        FailureSummary failure = new FailureSummary();
        failure.setReasonCode("PROVIDER_TIMEOUT");
        failure.setReasonMessage("timeout");
        failure.setFailedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setFailure(failure);

        assertThatThrownBy(() -> service.markFulfillmentFailed(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_ATTEMPT_MISMATCH);
    }

    @Test
    void markFulfillmentFailed_withoutDiagnosticTags_throwsInvariantBroken() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        MarkFulfillmentFailedRequest request = failedRequest("attempt-001");
        request.setDiagnosticTrace(diagnosticTraceWithoutTags("trace-003", "PROVIDER_CALL_FAILED", "2026-04-24T10:00:00Z"));

        assertThatThrownBy(() -> service.markFulfillmentFailed(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    @Test
    void recordFulfillmentResultLeftProcessing_happyPath_keepsProcessingAndAppendsAudit() {
        FulfillmentRecordMapper fulfillmentMapper = mock(FulfillmentRecordMapper.class);
        FulfillmentAttemptRecordMapper attemptMapper = mock(FulfillmentAttemptRecordMapper.class);
        TicketOrderMapper orderMapper = mock(TicketOrderMapper.class);
        AuditTrailService auditTrailService = mock(AuditTrailService.class);
        FulfillmentService service = new FulfillmentService(
                fulfillmentMapper, attemptMapper, orderMapper, new ObjectMapper(), auditTrailService);
        FulfillmentRecord processing = processingFulfillment("attempt-001");
        FulfillmentAttemptRecord attempt = attempt("attempt-001");
        when(fulfillmentMapper.selectById("ful-001")).thenReturn(processing);
        when(attemptMapper.selectById("attempt-001")).thenReturn(attempt);
        when(fulfillmentMapper.recordLeftProcessing(eq("ful-001"), eq("attempt-001"), any(String.class), eq(1L))).thenReturn(1);
        when(attemptMapper.recordLeftProcessing(eq("ful-001"), eq("attempt-001"), any(String.class))).thenReturn(1);
        when(orderMapper.selectById("order-001")).thenReturn(order("order-001", "trade-001"));

        RecordFulfillmentResultLeftProcessingRequest request = new RecordFulfillmentResultLeftProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setObservedAt(OffsetDateTime.parse("2026-04-24T10:10:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-004", "RESULT_LEFT_PROCESSING", "2026-04-24T10:10:00Z"));

        RecordFulfillmentResultLeftProcessingResponse response = service.recordFulfillmentResultLeftProcessing(request);

        assertThat(response.getStatus()).isEqualTo("PROCESSING");
        assertThat(response.getLastObservedAt()).isEqualTo(request.getObservedAt());
        ArgumentCaptor<AuditTrailEvent> auditCaptor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getEventType()).isEqualTo("FULFILLMENT_RESULT_LEFT_PROCESSING");
    }

    @Test
    void recordFulfillmentResultLeftProcessing_requiresExplicitDecision() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        RecordFulfillmentResultLeftProcessingRequest request = new RecordFulfillmentResultLeftProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setObservedAt(OffsetDateTime.parse("2026-04-24T10:10:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-004", "RESULT_COMMITTED", "2026-04-24T10:10:00Z"));

        assertThatThrownBy(() -> service.recordFulfillmentResultLeftProcessing(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    @Test
    void recordFulfillmentResultLeftProcessing_withoutDiagnosticTags_throwsInvariantBroken() {
        FulfillmentService service = new FulfillmentService(
                mock(FulfillmentRecordMapper.class),
                mock(FulfillmentAttemptRecordMapper.class),
                mock(TicketOrderMapper.class),
                new ObjectMapper(),
                mock(AuditTrailService.class));
        RecordFulfillmentResultLeftProcessingRequest request = new RecordFulfillmentResultLeftProcessingRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setObservedAt(OffsetDateTime.parse("2026-04-24T10:10:00Z"));
        request.setDiagnosticTrace(diagnosticTraceWithoutTags("trace-004", "RESULT_LEFT_PROCESSING", "2026-04-24T10:10:00Z"));

        assertThatThrownBy(() -> service.recordFulfillmentResultLeftProcessing(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    private FulfillmentRecordMapper mapperProxy(AtomicReference<String> invokedMethod,
                                                FulfillmentRecord selectOneResult,
                                                AtomicReference<FulfillmentRecord> insertedRecord) {
        return (FulfillmentRecordMapper) Proxy.newProxyInstance(
                FulfillmentRecordMapper.class.getClassLoader(),
                new Class[]{FulfillmentRecordMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        invokedMethod.set("selectOne");
                        return selectOneResult;
                    }
                    if ("insert".equals(method.getName())) {
                        insertedRecord.set((FulfillmentRecord) args[0]);
                        return 1;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(method.getName())) {
                        return "FulfillmentRecordMapperProxy";
                    }
                    return null;
                });
    }

    private FulfillmentRecord pendingFulfillment() {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId("ful-001");
        record.setOrderId("order-001");
        record.setStatus("PENDING");
        record.setVersion(0L);
        record.setCreatedAt(LocalDateTime.of(2026, 4, 24, 9, 0));
        return record;
    }

    private FulfillmentRecord processingFulfillment(String attemptId) {
        FulfillmentRecord record = pendingFulfillment();
        record.setStatus("PROCESSING");
        record.setCurrentAttemptId(attemptId);
        record.setProcessingStartedAt(LocalDateTime.of(2026, 4, 24, 9, 30));
        record.setExecutionPath("DEFAULT_PROVIDER");
        record.setVersion(1L);
        return record;
    }

    private FulfillmentAttemptRecord attempt(String attemptId) {
        FulfillmentAttemptRecord record = new FulfillmentAttemptRecord();
        record.setAttemptId(attemptId);
        record.setFulfillmentId("ful-001");
        record.setAttemptNo(1);
        record.setStatus("EXECUTING");
        record.setDispatcherRunId("scan-001");
        record.setExecutorRef("worker-A");
        record.setExecutionPath("DEFAULT_PROVIDER");
        record.setClaimedAt(LocalDateTime.of(2026, 4, 24, 9, 30));
        record.setStartedAt(LocalDateTime.of(2026, 4, 24, 9, 30));
        return record;
    }

    private TicketOrder order(String orderId, String externalTradeNo) {
        TicketOrder order = new TicketOrder();
        order.setOrderId(orderId);
        order.setExternalTradeNo(externalTradeNo);
        order.setStatus("CONFIRMED");
        return order;
    }

    private DiagnosticTrace diagnosticTrace(String traceId, String decision, String observedAt) {
        DiagnosticTrace trace = new DiagnosticTrace();
        trace.setTraceId(traceId);
        trace.setDecision(decision);
        trace.setObservedAt(OffsetDateTime.parse(observedAt).withOffsetSameInstant(ZoneOffset.UTC));
        trace.setTags(Map.of("source", "test"));
        return trace;
    }

    private DiagnosticTrace diagnosticTraceWithoutTags(String traceId, String decision, String observedAt) {
        DiagnosticTrace trace = diagnosticTrace(traceId, decision, observedAt);
        trace.setTags(null);
        return trace;
    }

    private MarkFulfillmentSucceededRequest succeededRequest() {
        MarkFulfillmentSucceededRequest request = new MarkFulfillmentSucceededRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId("attempt-001");
        request.setExpectedVersion(1L);
        request.setSucceededAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-002", "RESULT_COMMITTED", "2026-04-24T10:00:00Z"));
        request.setDeliveryResult(validDeliveryResult());
        return request;
    }

    private MarkFulfillmentFailedRequest failedRequest(String attemptId) {
        MarkFulfillmentFailedRequest request = new MarkFulfillmentFailedRequest();
        request.setFulfillmentId("ful-001");
        request.setAttemptId(attemptId);
        request.setExpectedVersion(1L);
        request.setFailedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        request.setDiagnosticTrace(diagnosticTrace("trace-003", "PROVIDER_CALL_FAILED", "2026-04-24T10:00:00Z"));
        request.setFailure(validFailureSummary());
        return request;
    }

    private DeliveryResult validDeliveryResult() {
        DeliveryResult deliveryResult = new DeliveryResult();
        deliveryResult.setResourceType("TICKET");
        deliveryResult.setResourceId("ticket-001");
        deliveryResult.setPayloadSummary(Map.of("seat", "A-1"));
        deliveryResult.setDeliveredAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        return deliveryResult;
    }

    private FailureSummary validFailureSummary() {
        FailureSummary failureSummary = new FailureSummary();
        failureSummary.setReasonCode("PROVIDER_TIMEOUT");
        failureSummary.setReasonMessage("timeout");
        failureSummary.setFailedAt(OffsetDateTime.parse("2026-04-24T10:00:00Z"));
        return failureSummary;
    }
}
