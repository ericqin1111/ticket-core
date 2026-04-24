package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class Fulfillment {
    private String fulfillmentId;
    private String orderId;
    private String status;
    private String currentAttemptId;
    private String latestAttemptId;
    private OffsetDateTime processingStartedAt;
    private OffsetDateTime processingTimeoutAt;
    private OffsetDateTime terminalAt;
    private String executionPath;
    private DeliveryResult deliveryResult;
    private FailureSummary lastFailure;
    private DiagnosticTrace lastDiagnosticTrace;
    private RetryPolicySnapshot retryPolicy;
    private RetryState retryState;
    private ProcessingLease currentProcessingLease;
    private FailureDecision latestFailure;
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
