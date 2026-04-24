package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class GovernProcessingTimeoutCommand {
    private String fulfillmentId;
    private String idempotencyKey;
    private Long expectedVersion;
    private OffsetDateTime now;
    private SafeToRetryEvidence safeToRetryEvidence;
}
