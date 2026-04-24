package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RecordAttemptSuccessCommand {
    private String fulfillmentId;
    private String attemptId;
    private String idempotencyKey;
    private Long expectedVersion;
    private OffsetDateTime now;
}
