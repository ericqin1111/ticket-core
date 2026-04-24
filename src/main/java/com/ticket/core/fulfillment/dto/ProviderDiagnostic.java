package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProviderDiagnostic {
    private String providerCode;
    private String providerMessage;
    private Boolean rawOutcomeKnown;
}
