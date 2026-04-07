package com.ticket.core.order.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class CreateOrderResponse {
    private String orderId;
    private String externalTradeNo;
    private String reservationId;
    /** PENDING_PAYMENT */
    private String status;
    private OffsetDateTime paymentDeadlineAt;
}
