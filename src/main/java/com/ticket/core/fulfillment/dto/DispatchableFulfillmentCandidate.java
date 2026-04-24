package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class DispatchableFulfillmentCandidate {
    private String fulfillmentId;
    private String orderId;
    private String status;
    private OffsetDateTime createdAt;
    private Long version;
}
