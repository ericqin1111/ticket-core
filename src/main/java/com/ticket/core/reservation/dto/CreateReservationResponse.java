package com.ticket.core.reservation.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class CreateReservationResponse {
    private String reservationId;
    private String externalTradeNo;
    private String catalogItemId;
    private Integer quantity;
    /** ACTIVE, CONSUMED, EXPIRED */
    private String status;
    private OffsetDateTime expiresAt;
}
