package com.ticket.core.payment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentConfirmationDtoJacksonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void request_acceptsSnakeCasePayload() throws Exception {
        String json = """
                {
                  "external_trade_no": "trade-confirm-001",
                  "payment_provider": "WECHAT_PAY",
                  "provider_event_id": "evt-001",
                  "provider_payment_id": "pay-001",
                  "confirmed_at": "2026-04-07T16:30:00+08:00",
                  "channel_context": {
                    "channel": "wechat",
                    "trace_id": "trace-001"
                  }
                }
                """;

        PaymentConfirmationRequest request = objectMapper.readValue(json, PaymentConfirmationRequest.class);

        assertThat(request.getExternalTradeNo()).isEqualTo("trade-confirm-001");
        assertThat(request.getPaymentProvider()).isEqualTo("WECHAT_PAY");
        assertThat(request.getProviderEventId()).isEqualTo("evt-001");
        assertThat(request.getProviderPaymentId()).isEqualTo("pay-001");
        assertThat(request.getConfirmedAt()).isEqualTo(OffsetDateTime.parse("2026-04-07T16:30:00+08:00"));
        assertThat(request.getChannelContext()).containsEntry("channel", "wechat");
        assertThat(request.getChannelContext()).containsEntry("trace_id", "trace-001");
    }

    @Test
    void request_stillAcceptsCamelCasePayload() throws Exception {
        String json = """
                {
                  "externalTradeNo": "trade-confirm-001",
                  "paymentProvider": "WECHAT_PAY",
                  "providerEventId": "evt-001",
                  "providerPaymentId": "pay-001",
                  "confirmedAt": "2026-04-07T16:30:00+08:00",
                  "channelContext": {
                    "channel": "wechat",
                    "traceId": "trace-001"
                  }
                }
                """;

        PaymentConfirmationRequest request = objectMapper.readValue(json, PaymentConfirmationRequest.class);

        assertThat(request.getExternalTradeNo()).isEqualTo("trade-confirm-001");
        assertThat(request.getPaymentProvider()).isEqualTo("WECHAT_PAY");
        assertThat(request.getProviderEventId()).isEqualTo("evt-001");
        assertThat(request.getProviderPaymentId()).isEqualTo("pay-001");
        assertThat(request.getConfirmedAt()).isEqualTo(OffsetDateTime.parse("2026-04-07T16:30:00+08:00"));
        assertThat(request.getChannelContext()).containsEntry("traceId", "trace-001");
    }

    @Test
    void response_serializesAsSnakeCasePayload() throws Exception {
        PaymentConfirmationResponse response = PaymentConfirmationResponse.builder()
                .orderId("order-confirm-001")
                .externalTradeNo("trade-confirm-001")
                .orderStatus("CONFIRMED")
                .fulfillmentId("fulfillment-001")
                .fulfillmentStatus("PENDING")
                .paymentConfirmationStatus("APPLIED")
                .confirmedAt(OffsetDateTime.parse("2026-04-07T08:30:00Z"))
                .build();

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.has("order_id")).isTrue();
        assertThat(json.has("external_trade_no")).isTrue();
        assertThat(json.has("order_status")).isTrue();
        assertThat(json.has("fulfillment_id")).isTrue();
        assertThat(json.has("fulfillment_status")).isTrue();
        assertThat(json.has("payment_confirmation_status")).isTrue();
        assertThat(json.has("confirmed_at")).isTrue();
        assertThat(json.has("orderId")).isFalse();
        assertThat(json.has("externalTradeNo")).isFalse();
    }
}
