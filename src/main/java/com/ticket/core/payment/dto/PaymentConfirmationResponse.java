package com.ticket.core.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class PaymentConfirmationResponse {
    private String orderId;
    private String externalTradeNo;
    /** CONFIRMED */
    private String orderStatus;
    private String fulfillmentId;
    /** PENDING */
    private String fulfillmentStatus;
    /** APPLIED, REPLAYED */
    private String paymentConfirmationStatus;
    private OffsetDateTime confirmedAt;
}
