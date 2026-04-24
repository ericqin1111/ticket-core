package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class DiagnosticTrace {
    private String traceId;
    private String decision;
    private String providerCorrelationKey;
    private String externalCallRef;
    private String errorDetailSummary;
    private OffsetDateTime observedAt;
    private Map<String, String> tags;
}
