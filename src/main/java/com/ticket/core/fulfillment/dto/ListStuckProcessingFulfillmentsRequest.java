package com.ticket.core.fulfillment.dto;

import lombok.Data;

import java.time.Duration;

@Data
public class ListStuckProcessingFulfillmentsRequest {
    private Duration olderThan;
    private int batchSize;
}
