package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class FailureSummary {
    private String reasonCode;
    private String reasonMessage;
    private OffsetDateTime failedAt;
}
