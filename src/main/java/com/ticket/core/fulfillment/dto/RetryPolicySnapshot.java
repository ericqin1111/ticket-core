package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetryPolicySnapshot {
    private Integer fastRetryLimit;
    private Integer backoffRetryLimit;
    private Integer totalRetryBudget;
    private List<String> backoffSchedule;
}
