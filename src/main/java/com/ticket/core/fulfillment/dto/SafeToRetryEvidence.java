package com.ticket.core.fulfillment.dto;

import lombok.Data;

@Data
public class SafeToRetryEvidence {
    private Boolean confirmedNotSucceeded;
    private Boolean duplicateExecutionRiskControllable;
}
