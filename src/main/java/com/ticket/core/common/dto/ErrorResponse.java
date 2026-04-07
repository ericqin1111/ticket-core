package com.ticket.core.common.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorResponse {
    private String code;
    private String message;
    private String requestId;
    private Boolean retryable;
}
