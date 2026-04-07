package com.ticket.core.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class PaymentConfirmationRequest {

    @NotBlank
    private String externalTradeNo;

    @NotBlank
    private String paymentProvider;

    @NotBlank
    private String providerEventId;

    private String providerPaymentId;

    @NotNull
    private OffsetDateTime confirmedAt;

    private Map<String, String> channelContext;
}
