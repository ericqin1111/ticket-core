package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class GovernanceAuditRecord {
    private String auditId;
    private String fulfillmentId;
    private String attemptId;
    private String actionType;
    private String fromStatus;
    private String toStatus;
    private String failureCategory;
    private String reasonCode;
    private RetryState retryBudgetSnapshot;
    private String actorType;
    private OffsetDateTime occurredAt;
}
