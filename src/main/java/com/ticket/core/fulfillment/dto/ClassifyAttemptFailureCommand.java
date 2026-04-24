package com.ticket.core.fulfillment.dto;

import lombok.Data;

@Data
public class ClassifyAttemptFailureCommand {
    private String fulfillmentId;
    private String attemptId;
    private String idempotencyKey;
    private Long expectedVersion;
    private ObservedFailure observedFailure;
}
