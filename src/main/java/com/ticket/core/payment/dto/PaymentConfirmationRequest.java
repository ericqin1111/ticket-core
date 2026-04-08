package com.ticket.core.payment.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class PaymentConfirmationRequest {

    @NotBlank
    @JsonProperty("external_trade_no")
    @JsonAlias("externalTradeNo")
    private String externalTradeNo;

    @NotBlank
    @JsonProperty("payment_provider")
    @JsonAlias("paymentProvider")
    private String paymentProvider;

    @NotBlank
    @JsonProperty("provider_event_id")
    @JsonAlias("providerEventId")
    private String providerEventId;

    @JsonProperty("provider_payment_id")
    @JsonAlias("providerPaymentId")
    private String providerPaymentId;

    @NotNull
    @JsonProperty("confirmed_at")
    @JsonAlias("confirmedAt")
    private OffsetDateTime confirmedAt;

    @JsonProperty("channel_context")
    @JsonAlias("channelContext")
    private Map<String, String> channelContext;
}
