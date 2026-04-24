package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class FulfillmentAttempt {
    private String attemptId;
    private String fulfillmentId;
    private Integer attemptNo;
    private Integer sequenceNo;
    private String trigger;
    private String executionStatus;
    private String status;
    private String dispatcherRunId;
    private String executorRef;
    private String executionPath;
    private OffsetDateTime claimedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private OffsetDateTime finishedAt;
    private DeliveryResult deliveryResult;
    private FailureSummary failure;
    private FailureDecision failureDecision;
    private ProviderDiagnostic providerDiagnostic;
    private DiagnosticTrace diagnosticTrace;
}
