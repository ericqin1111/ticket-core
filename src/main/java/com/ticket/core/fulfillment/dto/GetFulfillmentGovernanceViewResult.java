package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetFulfillmentGovernanceViewResult {
    private Fulfillment fulfillment;
    private FulfillmentAttempt latestAttempt;
    private FailureDecision latestFailure;
    private RetryState retryState;
    private List<GovernanceAuditRecord> recentAuditRecords;
}
