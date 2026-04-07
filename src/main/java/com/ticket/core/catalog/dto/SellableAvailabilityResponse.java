package com.ticket.core.catalog.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class SellableAvailabilityResponse {
    private String catalogItemId;
    private String inventoryResourceId;
    private Integer sellableQuantity;
    private Integer reservedQuantity;
    /** SELLABLE, LOW_STOCK, SOLD_OUT, OFF_SHELF */
    private String status;
    private OffsetDateTime checkedAt;
}
