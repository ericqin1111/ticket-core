package com.ticket.core.reservation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateReservationRequest {

    @NotBlank
    private String externalTradeNo;

    @NotBlank
    private String catalogItemId;

    @NotNull
    @Min(1)
    private Integer quantity;

    @NotNull
    @Min(30)
    private Integer reservationTtlSeconds;

    private Map<String, String> channelContext;
}
