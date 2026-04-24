package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecordAttemptSuccessResult {
    private Fulfillment fulfillment;
    private FulfillmentAttempt attempt;
    private List<GovernanceAuditRecord> emittedAuditRecords;
}
