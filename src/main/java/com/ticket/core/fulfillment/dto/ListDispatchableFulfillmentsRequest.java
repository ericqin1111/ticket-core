package com.ticket.core.fulfillment.dto;

import lombok.Data;

@Data
public class ListDispatchableFulfillmentsRequest {
    private String scanId;
    private int batchSize;
    private String cursor;
    private String orderedBy;
}
