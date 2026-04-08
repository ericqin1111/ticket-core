package com.ticket.core.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class PaymentConfirmationResponse {
    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("external_trade_no")
    private String externalTradeNo;

    /** CONFIRMED */
    @JsonProperty("order_status")
    private String orderStatus;

    @JsonProperty("fulfillment_id")
    private String fulfillmentId;

    /** PENDING */
    @JsonProperty("fulfillment_status")
    private String fulfillmentStatus;

    /** APPLIED, REPLAYED */
    @JsonProperty("payment_confirmation_status")
    private String paymentConfirmationStatus;

    @JsonProperty("confirmed_at")
    private OffsetDateTime confirmedAt;
}
