package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GovernProcessingTimeoutResult {
    private Fulfillment fulfillment;
    private FulfillmentAttempt attempt;
    private FailureDecision decision;
    private List<GovernanceAuditRecord> emittedAuditRecords;
}
