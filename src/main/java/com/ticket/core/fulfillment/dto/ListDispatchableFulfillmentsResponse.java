package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ListDispatchableFulfillmentsResponse {
    private List<DispatchableFulfillmentCandidate> candidates;
    private String nextCursor;
}
