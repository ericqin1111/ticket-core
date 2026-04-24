package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class ScheduleRetryAfterFailureResult {
    private Fulfillment fulfillment;
    private String scheduledAttemptTrigger;
    private OffsetDateTime nextRetryAt;
    private List<GovernanceAuditRecord> emittedAuditRecords;
}
