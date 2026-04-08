package com.ticket.core.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.common.dto.ErrorResponse;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.payment.dto.PaymentConfirmationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Confirmation API — POST /payments/confirmations")
class PaymentConfirmationApiIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> buildConfirmationBody(String externalTradeNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("externalTradeNo", externalTradeNo);
        body.put("paymentProvider", "WECHAT_PAY");
        body.put("providerEventId", "evt-" + externalTradeNo);
        body.put("providerPaymentId", "pay-" + externalTradeNo);
        body.put("confirmedAt", "2026-04-07T16:30:00+08:00");
        Map<String, String> channelContext = new HashMap<>();
        channelContext.put("channel", "wechat");
        channelContext.put("traceId", "trace-" + externalTradeNo);
        body.put("channelContext", channelContext);
        return body;
    }

    private HttpEntity<String> rawJsonWithIdempotencyKey(String rawJson, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(rawJson, headers);
    }

    @Test
    @DisplayName("Valid request confirms PENDING_PAYMENT order and creates one PENDING fulfillment")
    void confirmPayment_happyPath_returns200AppliedAndPersistsProjection() {
        insertOrder("order-pay-hp-001", "trade-pay-hp-001", "res-pay-hp-001");

        ResponseEntity<PaymentConfirmationResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-hp-001"), UUID.randomUUID().toString()),
                PaymentConfirmationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentConfirmationResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getOrderId()).isEqualTo("order-pay-hp-001");
        assertThat(body.getExternalTradeNo()).isEqualTo("trade-pay-hp-001");
        assertThat(body.getOrderStatus()).isEqualTo("CONFIRMED");
        assertThat(body.getFulfillmentId()).isNotBlank();
        assertThat(body.getFulfillmentStatus()).isEqualTo("PENDING");
        assertThat(body.getPaymentConfirmationStatus()).isEqualTo("APPLIED");
        assertThat(body.getConfirmedAt()).isNotNull();

        TicketOrder order = ticketOrderMapper.selectById("order-pay-hp-001");
        assertThat(order.getStatus()).isEqualTo("CONFIRMED");
        assertThat(order.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 4, 7, 8, 30));

        FulfillmentRecord fulfillment = fulfillmentRecordMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentRecord>().eq(FulfillmentRecord::getOrderId, "order-pay-hp-001"));
        assertThat(fulfillment).isNotNull();
        assertThat(fulfillment.getStatus()).isEqualTo("PENDING");
        assertThat(fulfillment.getPaymentProvider()).isEqualTo("WECHAT_PAY");
        assertThat(fulfillment.getProviderEventId()).isEqualTo("evt-trade-pay-hp-001");
        assertThat(fulfillment.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 4, 7, 8, 30));
    }

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400")
    void confirmPayment_missingIdempotencyKeyHeader_returns400() {
        insertOrder("order-pay-hdr-001", "trade-pay-hdr-001", "res-pay-hdr-001");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withoutIdempotencyKey(buildConfirmationBody("trade-pay-hdr-001")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Missing confirmedAt returns 400 VALIDATION_ERROR")
    void confirmPayment_missingConfirmedAt_returns400() {
        insertOrder("order-pay-val-001", "trade-pay-val-001", "res-pay-val-001");
        Map<String, Object> body = buildConfirmationBody("trade-pay-val-001");
        body.remove("confirmedAt");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Unknown external_trade_no returns 404 ORDER_NOT_FOUND")
    void confirmPayment_orderNotFound_returns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-nf-001"), UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("CLOSED order returns 409 ORDER_NOT_CONFIRMABLE")
    void confirmPayment_closedOrder_returns409() {
        insertOrder("order-pay-closed-001", "trade-pay-closed-001", "res-pay-closed-001", "CLOSED", null);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-closed-001"), UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("ORDER_NOT_CONFIRMABLE");
    }

    @Test
    @DisplayName("Same Idempotency-Key replays cached success with REPLAYED status")
    void confirmPayment_sameIdempotencyKey_returnsReplayed() {
        insertOrder("order-pay-replay-001", "trade-pay-replay-001", "res-pay-replay-001");
        String idemKey = UUID.randomUUID().toString();

        ResponseEntity<PaymentConfirmationResponse> first = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-replay-001"), idemKey),
                PaymentConfirmationResponse.class);
        ResponseEntity<PaymentConfirmationResponse> second = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-replay-001"), idemKey),
                PaymentConfirmationResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().getPaymentConfirmationStatus()).isEqualTo("REPLAYED");
        assertThat(second.getBody().getFulfillmentId()).isEqualTo(first.getBody().getFulfillmentId());
    }

    @Test
    @DisplayName("Different Idempotency-Key for already CONFIRMED order returns REPLAYED without creating second fulfillment")
    void confirmPayment_differentIdempotencyKeyForConfirmedOrder_returnsReplayed() {
        insertOrder("order-pay-confirmed-001", "trade-pay-confirmed-001", "res-pay-confirmed-001",
                "CONFIRMED", LocalDateTime.of(2026, 4, 7, 8, 30));
        insertFulfillment("ful-pay-confirmed-001", "order-pay-confirmed-001", LocalDateTime.of(2026, 4, 7, 8, 30));

        ResponseEntity<PaymentConfirmationResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-confirmed-001"), UUID.randomUUID().toString()),
                PaymentConfirmationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPaymentConfirmationStatus()).isEqualTo("REPLAYED");
        assertThat(response.getBody().getFulfillmentId()).isEqualTo("ful-pay-confirmed-001");

        Long fulfillmentCount = fulfillmentRecordMapper.selectCount(
                new LambdaQueryWrapper<FulfillmentRecord>().eq(FulfillmentRecord::getOrderId, "order-pay-confirmed-001"));
        assertThat(fulfillmentCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Confirmed order without fulfillment returns 409 FULFILLMENT_INVARIANT_BROKEN")
    void confirmPayment_confirmedOrderWithoutFulfillment_returns409() {
        insertOrder("order-pay-broken-001", "trade-pay-broken-001", "res-pay-broken-001",
                "CONFIRMED", LocalDateTime.of(2026, 4, 7, 8, 30));

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-broken-001"), UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("FULFILLMENT_INVARIANT_BROKEN");
    }

    @Test
    @DisplayName("Same Idempotency-Key with different body returns 409 IDEMPOTENCY_CONFLICT")
    void confirmPayment_sameIdempotencyKeyDifferentBody_returns409() {
        insertOrder("order-pay-conflict-001", "trade-pay-conflict-001", "res-pay-conflict-001");
        String idemKey = UUID.randomUUID().toString();

        restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody("trade-pay-conflict-001"), idemKey),
                PaymentConfirmationResponse.class);

        Map<String, Object> differentBody = buildConfirmationBody("trade-pay-conflict-001");
        differentBody.put("providerEventId", "evt-different");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(differentBody, idemKey),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("RFC snake_case request contract should be accepted and return snake_case fields")
    void confirmPayment_rfcSnakeCaseContract_returnsSnakeCasePayload() throws Exception {
        insertOrder("order-pay-contract-001", "trade-pay-contract-001", "res-pay-contract-001");

        String requestBody = """
                {
                  "external_trade_no": "trade-pay-contract-001",
                  "payment_provider": "WECHAT_PAY",
                  "provider_event_id": "evt-trade-pay-contract-001",
                  "provider_payment_id": "pay-trade-pay-contract-001",
                  "confirmed_at": "2026-04-07T16:30:00+08:00",
                  "channel_context": {
                    "channel": "wechat",
                    "trace_id": "trace-trade-pay-contract-001"
                  }
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/payments/confirmations",
                rawJsonWithIdempotencyKey(requestBody, UUID.randomUUID().toString()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("external_trade_no")).isTrue();
        assertThat(json.has("order_id")).isTrue();
        assertThat(json.has("order_status")).isTrue();
        assertThat(json.has("fulfillment_id")).isTrue();
        assertThat(json.has("fulfillment_status")).isTrue();
        assertThat(json.has("payment_confirmation_status")).isTrue();
        assertThat(json.has("confirmed_at")).isTrue();
    }

    @Test
    @DisplayName("Concurrent confirmations with different Idempotency-Key keep one fulfillment invariant")
    void confirmPayment_concurrentDifferentIdempotencyKeys_keepsSingleFulfillmentInvariant() throws Exception {
        insertOrder("order-pay-concurrent-001", "trade-pay-concurrent-001", "res-pay-concurrent-001");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> postConcurrentConfirmation(
                    "trade-pay-concurrent-001", ready, start));
            Future<ResponseEntity<String>> second = executor.submit(() -> postConcurrentConfirmation(
                    "trade-pay-concurrent-001", ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ResponseEntity<String> firstResponse = first.get(10, TimeUnit.SECONDS);
            ResponseEntity<String> secondResponse = second.get(10, TimeUnit.SECONDS);

            assertConcurrentOutcome(firstResponse);
            assertConcurrentOutcome(secondResponse);

            Long fulfillmentCount = fulfillmentRecordMapper.selectCount(
                    new LambdaQueryWrapper<FulfillmentRecord>()
                            .eq(FulfillmentRecord::getOrderId, "order-pay-concurrent-001"));
            assertThat(fulfillmentCount).isEqualTo(1L);

            TicketOrder order = ticketOrderMapper.selectById("order-pay-concurrent-001");
            assertThat(order).isNotNull();
            assertThat(order.getStatus()).isEqualTo("CONFIRMED");
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<String> postConcurrentConfirmation(
            String externalTradeNo, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return restTemplate.postForEntity(
                "/payments/confirmations",
                withIdempotencyKey(buildConfirmationBody(externalTradeNo), UUID.randomUUID().toString()),
                String.class);
    }

    private void assertConcurrentOutcome(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isIn(200, 409);
        JsonNode json = objectMapper.readTree(response.getBody());
        if (response.getStatusCode().is2xxSuccessful()) {
            assertThat(json.path("paymentConfirmationStatus").asText()).isIn("APPLIED", "REPLAYED");
            return;
        }
        assertThat(json.path("code").asText()).isEqualTo("PAYMENT_CONFIRMATION_IN_PROGRESS");
        assertThat(json.path("retryable").asBoolean()).isTrue();
    }
}
