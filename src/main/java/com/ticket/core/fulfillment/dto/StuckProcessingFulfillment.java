package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class StuckProcessingFulfillment {
    private String fulfillmentId;
    private String orderId;
    private String currentAttemptId;
    private OffsetDateTime processingStartedAt;
    private DiagnosticTrace diagnosticTrace;
}
