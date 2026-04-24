package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ScheduleRetryAfterFailureCommand {
    private String fulfillmentId;
    private String idempotencyKey;
    private Long expectedVersion;
    private String requestedMode;
    private OffsetDateTime now;
}
