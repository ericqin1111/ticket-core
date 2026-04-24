package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ClaimFulfillmentForProcessingRequest {
    private String fulfillmentId;
    private long expectedVersion;
    private String dispatcherRunId;
    private String executorRef;
    private String executionPath;
    private OffsetDateTime claimedAt;
    private DiagnosticTrace diagnosticTrace;
}
