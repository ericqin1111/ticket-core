package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class MarkFulfillmentSucceededRequest {
    private String fulfillmentId;
    private String attemptId;
    private long expectedVersion;
    private DeliveryResult deliveryResult;
    private DiagnosticTrace diagnosticTrace;
    private OffsetDateTime succeededAt;
}
