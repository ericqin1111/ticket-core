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
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "The idempotency key was used with a different request payload.", HttpStatus.CONFLICT, false),
    INVALID_STATUS_TRANSITION("INVALID_STATUS_TRANSITION", "The fulfillment cannot transition from its current status using this operation.", HttpStatus.CONFLICT, false),
    ATTEMPT_ALREADY_FINALIZED("ATTEMPT_ALREADY_FINALIZED", "The fulfillment attempt has already reached a terminal execution status.", HttpStatus.CONFLICT, false),
    FAILURE_DECISION_REQUIRED("FAILURE_DECISION_REQUIRED", "The observed failure data is insufficient to derive a unique failure decision.", HttpStatus.UNPROCESSABLE_ENTITY, false),
    FAILURE_CATEGORY_NOT_RETRYABLE("FAILURE_CATEGORY_NOT_RETRYABLE", "The latest failure category does not allow automatic retry scheduling.", HttpStatus.CONFLICT, false),
    RETRY_BUDGET_EXHAUSTED("RETRY_BUDGET_EXHAUSTED", "The configured retry budget has already been exhausted.", HttpStatus.CONFLICT, false),
    FAST_RETRY_ALREADY_USED("FAST_RETRY_ALREADY_USED", "The single fast retry has already been consumed.", HttpStatus.CONFLICT, false),
    NEXT_RETRY_NOT_DUE("NEXT_RETRY_NOT_DUE", "The next retry is not yet due according to the backoff schedule.", HttpStatus.CONFLICT, true),
    PROCESSING_NOT_TIMED_OUT("PROCESSING_NOT_TIMED_OUT", "The current processing lease has not timed out yet.", HttpStatus.CONFLICT, true),
    IDEMPOTENT_REPLAY_CONFLICT("IDEMPOTENT_REPLAY_CONFLICT", "The same idempotency key is already being processed or was replayed with a conflicting state.", HttpStatus.CONFLICT, true),
    CONCURRENCY_VERSION_MISMATCH("CONCURRENCY_VERSION_MISMATCH", "The provided expected version does not match the latest fulfillment version.", HttpStatus.CONFLICT, true);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
    private final boolean retryable;
}
