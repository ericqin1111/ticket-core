package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class RetryState {
    private Integer fastRetryUsed;
    private Integer backoffRetryUsed;
    private Integer totalRetryUsed;
    private OffsetDateTime nextRetryAt;
    private Boolean budgetExhausted;
}
