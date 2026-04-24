package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class MarkFulfillmentFailedRequest {
    private String fulfillmentId;
    private String attemptId;
    private long expectedVersion;
    private FailureSummary failure;
    private DiagnosticTrace diagnosticTrace;
    private OffsetDateTime failedAt;
}
