package com.ticket.core.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.service.FulfillmentService;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import com.ticket.core.payment.dto.PaymentConfirmationRequest;
import com.ticket.core.payment.dto.PaymentConfirmationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    private static final String ACTION_NAME = "PAYMENT_CONFIRMATION";
    private static final String RESOURCE_TYPE = "FULFILLMENT";

    private final TicketOrderMapper ticketOrderMapper;
    private final IdempotencyService idempotencyService;
    private final FulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PaymentConfirmationResponse confirmPayment(String idempotencyKey, PaymentConfirmationRequest request) {
        String requestHash = idempotencyService.hashRequest(request);
        IdempotencyRecord idemRecord = idempotencyService.checkAndMarkProcessing(
                ACTION_NAME, idempotencyKey, requestHash, request.getExternalTradeNo());

        if ("SUCCEEDED".equals(idemRecord.getStatus())) {
            throw new UnsupportedOperationException("RFC-TKT001-02 Step 4 will implement replay success handling.");
        }

        TicketOrder order = ticketOrderMapper.selectByExternalTradeNo(request.getExternalTradeNo());
        if (order == null || !"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new UnsupportedOperationException("RFC-TKT001-02 Step 4 will implement replay and rejection branches.");
        }

        LocalDateTime confirmedAtUtc = request.getConfirmedAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        long currentVersion = order.getVersion() == null ? 0L : order.getVersion();
        int updated = ticketOrderMapper.confirmPendingPayment(order.getOrderId(), confirmedAtUtc, currentVersion);
        if (updated == 0) {
            throw new IllegalStateException("Order confirmation update did not succeed.");
        }

        FulfillmentRecord fulfillmentRecord = new FulfillmentRecord();
        fulfillmentRecord.setFulfillmentId(UUID.randomUUID().toString());
        fulfillmentRecord.setOrderId(order.getOrderId());
        fulfillmentRecord.setStatus("PENDING");
        fulfillmentRecord.setPaymentProvider(request.getPaymentProvider());
        fulfillmentRecord.setProviderEventId(request.getProviderEventId());
        fulfillmentRecord.setProviderPaymentId(request.getProviderPaymentId());
        fulfillmentRecord.setConfirmedAt(confirmedAtUtc);
        fulfillmentRecord.setVersion(0L);
        if (request.getChannelContext() != null) {
            fulfillmentRecord.setChannelContextJson(toJson(request.getChannelContext()));
        }
        fulfillmentService.create(fulfillmentRecord);

        PaymentConfirmationResponse response = PaymentConfirmationResponse.builder()
                .orderId(order.getOrderId())
                .externalTradeNo(order.getExternalTradeNo())
                .orderStatus("CONFIRMED")
                .fulfillmentId(fulfillmentRecord.getFulfillmentId())
                .fulfillmentStatus("PENDING")
                .paymentConfirmationStatus("APPLIED")
                .confirmedAt(confirmedAtUtc.atOffset(ZoneOffset.UTC))
                .build();

        idempotencyService.markSucceeded(idemRecord.getIdempotencyRecordId(),
                RESOURCE_TYPE, fulfillmentRecord.getFulfillmentId(), response);
        log.info("Payment confirmation applied: externalTradeNo={}, orderId={}, fulfillmentId={}",
                order.getExternalTradeNo(), order.getOrderId(), fulfillmentRecord.getFulfillmentId());
        return response;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payment confirmation channel context", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
