package com.ticket.core.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateOrderRequest {

    @NotBlank
    private String externalTradeNo;

    @NotBlank
    private String reservationId;

    @NotNull
    @Valid
    private BuyerDto buyer;

    private Map<String, String> submissionContext;
}
