package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class DeliveryResult {
    private String resourceType;
    private String resourceId;
    private Map<String, Object> payloadSummary;
    private OffsetDateTime deliveredAt;
}
