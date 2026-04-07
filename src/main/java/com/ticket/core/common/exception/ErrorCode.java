package com.ticket.core.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    CATALOG_ITEM_NOT_SELLABLE("CATALOG_ITEM_NOT_SELLABLE", "The catalog item is not available for sale.", HttpStatus.UNPROCESSABLE_ENTITY, false),
    INSUFFICIENT_INVENTORY("INSUFFICIENT_INVENTORY", "Insufficient inventory to fulfill the reservation.", HttpStatus.CONFLICT, false),
    RESERVATION_NOT_FOUND("RESERVATION_NOT_FOUND", "The specified reservation does not exist.", HttpStatus.NOT_FOUND, false),
    RESERVATION_ALREADY_CONSUMED("RESERVATION_ALREADY_CONSUMED", "The reservation has already been consumed.", HttpStatus.CONFLICT, false),
    RESERVATION_EXPIRED("RESERVATION_EXPIRED", "The reservation has expired.", HttpStatus.GONE, false),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was used with a different request payload.", HttpStatus.CONFLICT, false);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
    private final boolean retryable;
}
