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
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "The specified order does not exist.", HttpStatus.NOT_FOUND, false),
    ORDER_NOT_CONFIRMABLE("ORDER_NOT_CONFIRMABLE", "The order is not in a confirmable state.", HttpStatus.CONFLICT, false),
    PAYMENT_CONFIRMATION_IN_PROGRESS("PAYMENT_CONFIRMATION_IN_PROGRESS", "A payment confirmation for this request is already in progress.", HttpStatus.CONFLICT, true),
    FULFILLMENT_NOT_FOUND("FULFILLMENT_NOT_FOUND", "The specified fulfillment does not exist.", HttpStatus.NOT_FOUND, false),
    FULFILLMENT_NOT_CLAIMABLE("FULFILLMENT_NOT_CLAIMABLE", "The fulfillment is not in a claimable state.", HttpStatus.CONFLICT, false),
    FULFILLMENT_CLAIM_CONFLICT("FULFILLMENT_CLAIM_CONFLICT", "Another worker already claimed the fulfillment.", HttpStatus.CONFLICT, true),
    FULFILLMENT_ATTEMPT_MISMATCH("FULFILLMENT_ATTEMPT_MISMATCH", "The attempt does not hold execution ownership for this fulfillment.", HttpStatus.CONFLICT, false),
    FULFILLMENT_ALREADY_TERMINAL("FULFILLMENT_ALREADY_TERMINAL", "The fulfillment has already reached a terminal state.", HttpStatus.CONFLICT, false),
    DELIVERY_RESULT_REQUIRED("DELIVERY_RESULT_REQUIRED", "A delivery result is required when marking fulfillment as succeeded.", HttpStatus.CONFLICT, false),
    FAILURE_SUMMARY_REQUIRED("FAILURE_SUMMARY_REQUIRED", "A failure summary is required when marking fulfillment as failed.", HttpStatus.CONFLICT, false),
    FULFILLMENT_INVARIANT_BROKEN("FULFILLMENT_INVARIANT_BROKEN", "The fulfillment state violates a required invariant.", HttpStatus.CONFLICT, false),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was used with a different request payload.", HttpStatus.CONFLICT, false);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
    private final boolean retryable;
}
