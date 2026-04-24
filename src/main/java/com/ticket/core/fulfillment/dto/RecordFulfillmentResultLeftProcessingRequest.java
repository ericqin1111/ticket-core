package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RecordFulfillmentResultLeftProcessingRequest {
    private String fulfillmentId;
    private String attemptId;
    private long expectedVersion;
    private DiagnosticTrace diagnosticTrace;
    private OffsetDateTime observedAt;
}
