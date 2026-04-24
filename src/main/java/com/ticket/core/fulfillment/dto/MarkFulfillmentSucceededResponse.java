package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class MarkFulfillmentSucceededResponse {
    private String fulfillmentId;
    private String attemptId;
    private String status;
    private OffsetDateTime terminalAt;
}
