package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class StartRetryAttemptCommand {
    private String fulfillmentId;
    private String trigger;
    private String idempotencyKey;
    private Long expectedVersion;
    private OffsetDateTime now;
}
