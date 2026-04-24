package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ProcessingLease {
    private OffsetDateTime processingStartedAt;
    private OffsetDateTime timeoutAt;
}
