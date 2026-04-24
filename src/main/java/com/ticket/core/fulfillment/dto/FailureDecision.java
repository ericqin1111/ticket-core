package com.ticket.core.fulfillment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class FailureDecision {
    private String category;
    private String reasonCode;
    private String retryDisposition;
    private Boolean manualReviewRequired;
    private Boolean finalTerminationSuggested;
    private String rationale;
    private OffsetDateTime classifiedAt;
}
