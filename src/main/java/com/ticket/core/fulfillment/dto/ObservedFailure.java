package com.ticket.core.fulfillment.dto;

import lombok.Data;

@Data
public class ObservedFailure {
    private String providerCode;
    private String providerMessage;
    private Boolean rawOutcomeKnown;
    private String failureSignal;
}
