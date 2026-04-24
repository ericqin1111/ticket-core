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
    private String status;
    private String dispatcherRunId;
    private String executorRef;
    private String executionPath;
    private OffsetDateTime claimedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private DeliveryResult deliveryResult;
    private FailureSummary failure;
    private DiagnosticTrace diagnosticTrace;
}
