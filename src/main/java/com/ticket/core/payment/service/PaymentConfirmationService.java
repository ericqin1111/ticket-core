package com.ticket.core.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.service.AuditTrailService;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final AuditTrailService auditTrailService;

    @Transactional
    public PaymentConfirmationResponse confirmPayment(String idempotencyKey, PaymentConfirmationRequest request) {
        String requestHash = idempotencyService.hashRequest(request);
        IdempotencyRecord idemRecord = idempotencyService.checkAndMarkProcessing(
                ACTION_NAME, idempotencyKey, requestHash, request.getExternalTradeNo());

        if ("SUCCEEDED".equals(idemRecord.getStatus())) {
            PaymentConfirmationResponse replayed = asReplayed(
                    idempotencyService.replayResponse(idemRecord, PaymentConfirmationResponse.class));
            auditTrailService.append(buildPaymentConfirmationEvent(
                    "PAYMENT_CONFIRMATION_REPLAYED",
                    request,
                    replayed.getOrderId(),
                    replayed.getFulfillmentId(),
                    "PAYMENT_REPLAYED",
                    replayed.getConfirmedAt(),
                    idempotencyKey));
            return replayed;
        }
        if ("PROCESSING".equals(idemRecord.getStatus()) && !idemRecord.isNewlyCreated()) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIRMATION_IN_PROGRESS);
        }

        TicketOrder order = ticketOrderMapper.selectByExternalTradeNo(request.getExternalTradeNo());
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if ("CONFIRMED".equals(order.getStatus())) {
            return replayConfirmedOrder(order, idemRecord, request, idempotencyKey);
        }
        if ("CLOSED".equals(order.getStatus())) {
            auditTrailService.append(buildPaymentConfirmationRejectedEvent(order, request, idempotencyKey));
            throw new BusinessException(ErrorCode.ORDER_NOT_CONFIRMABLE);
        }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CONFIRMABLE);
        }

        LocalDateTime confirmedAtUtc = request.getConfirmedAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        long currentVersion = order.getVersion() == null ? 0L : order.getVersion();
        int updated = ticketOrderMapper.confirmPendingPayment(order.getOrderId(), confirmedAtUtc, currentVersion);
        if (updated == 0) {
            return resolveAfterConcurrentUpdate(request, idemRecord, idempotencyKey);
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

        auditTrailService.append(buildOrderConfirmedEvent(order, request, confirmedAtUtc.atOffset(ZoneOffset.UTC)));
        auditTrailService.append(buildFulfillmentCreatedEvent(order, fulfillmentRecord, request, confirmedAtUtc.atOffset(ZoneOffset.UTC)));
        auditTrailService.append(buildPaymentConfirmationEvent(
                "PAYMENT_CONFIRMATION_APPLIED",
                request,
                order.getOrderId(),
                fulfillmentRecord.getFulfillmentId(),
                "PAYMENT_CONFIRMED",
                confirmedAtUtc.atOffset(ZoneOffset.UTC),
                idempotencyKey));
        idempotencyService.markSucceeded(idemRecord.getIdempotencyRecordId(),
                RESOURCE_TYPE, fulfillmentRecord.getFulfillmentId(), response);
        log.info("Payment confirmation applied: externalTradeNo={}, orderId={}, fulfillmentId={}",
                order.getExternalTradeNo(), order.getOrderId(), fulfillmentRecord.getFulfillmentId());
        return response;
    }

    private PaymentConfirmationResponse replayConfirmedOrder(TicketOrder order, IdempotencyRecord idemRecord,
                                                             PaymentConfirmationRequest request, String idempotencyKey) {
        FulfillmentRecord fulfillmentRecord = fulfillmentService.findByOrderId(order.getOrderId());
        if (fulfillmentRecord == null || !"PENDING".equals(fulfillmentRecord.getStatus())) {
            throw new BusinessException(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
        }

        PaymentConfirmationResponse response = buildResponse(
                order.getOrderId(),
                order.getExternalTradeNo(),
                fulfillmentRecord.getFulfillmentId(),
                fulfillmentRecord.getConfirmedAt().atOffset(ZoneOffset.UTC),
                "REPLAYED");

        if (!"SUCCEEDED".equals(idemRecord.getStatus())) {
            auditTrailService.append(buildPaymentConfirmationEvent(
                    "PAYMENT_CONFIRMATION_REPLAYED",
                    request,
                    order.getOrderId(),
                    fulfillmentRecord.getFulfillmentId(),
                    "PAYMENT_REPLAYED",
                    fulfillmentRecord.getConfirmedAt().atOffset(ZoneOffset.UTC),
                    idempotencyKey));
            idempotencyService.markSucceeded(idemRecord.getIdempotencyRecordId(),
                    RESOURCE_TYPE, fulfillmentRecord.getFulfillmentId(), response);
        }
        return response;
    }

    private PaymentConfirmationResponse resolveAfterConcurrentUpdate(PaymentConfirmationRequest request,
                                                                    IdempotencyRecord idemRecord,
                                                                    String idempotencyKey) {
        TicketOrder latestOrder = ticketOrderMapper.selectByExternalTradeNo(request.getExternalTradeNo());
        if (latestOrder == null) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIRMATION_IN_PROGRESS);
        }
        if ("CONFIRMED".equals(latestOrder.getStatus())) {
            return replayConfirmedOrder(latestOrder, idemRecord, request, idempotencyKey);
        }
        if ("PENDING_PAYMENT".equals(latestOrder.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIRMATION_IN_PROGRESS);
        }
        if ("CLOSED".equals(latestOrder.getStatus())) {
            auditTrailService.append(buildPaymentConfirmationRejectedEvent(latestOrder, request, idempotencyKey));
        }
        throw new BusinessException(ErrorCode.ORDER_NOT_CONFIRMABLE);
    }

    private PaymentConfirmationResponse asReplayed(PaymentConfirmationResponse response) {
        return buildResponse(
                response.getOrderId(),
                response.getExternalTradeNo(),
                response.getFulfillmentId(),
                response.getConfirmedAt(),
                "REPLAYED");
    }

    private PaymentConfirmationResponse buildResponse(String orderId, String externalTradeNo,
                                                      String fulfillmentId, java.time.OffsetDateTime confirmedAt,
                                                      String confirmationStatus) {
        return PaymentConfirmationResponse.builder()
                .orderId(orderId)
                .externalTradeNo(externalTradeNo)
                .orderStatus("CONFIRMED")
                .fulfillmentId(fulfillmentId)
                .fulfillmentStatus("PENDING")
                .paymentConfirmationStatus(confirmationStatus)
                .confirmedAt(confirmedAt)
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payment confirmation channel context", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private AuditTrailEvent buildOrderConfirmedEvent(TicketOrder order, PaymentConfirmationRequest request,
                                                     OffsetDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("ORDER_CONFIRMED");
        event.setAggregateType("ORDER");
        event.setAggregateId(order.getOrderId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(order.getOrderId());
        event.setProviderEventId(request.getProviderEventId());
        event.setActorType("CHANNEL");
        event.setReasonCode("PAYMENT_CONFIRMED");
        event.setPayloadSummaryJson(toJson(buildPaymentPayloadSummary(request, "CONFIRMED")));
        event.setOccurredAt(toLocalDateTime(occurredAt));
        return event;
    }

    private AuditTrailEvent buildFulfillmentCreatedEvent(TicketOrder order, FulfillmentRecord fulfillmentRecord,
                                                         PaymentConfirmationRequest request, OffsetDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("FULFILLMENT_CREATED");
        event.setAggregateType("FULFILLMENT");
        event.setAggregateId(fulfillmentRecord.getFulfillmentId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(order.getOrderId());
        event.setFulfillmentId(fulfillmentRecord.getFulfillmentId());
        event.setProviderEventId(request.getProviderEventId());
        event.setActorType("CHANNEL");
        event.setReasonCode("PAYMENT_CONFIRMED");
        event.setPayloadSummaryJson(toJson(buildFulfillmentPayloadSummary(fulfillmentRecord)));
        event.setOccurredAt(toLocalDateTime(occurredAt));
        return event;
    }

    private AuditTrailEvent buildPaymentConfirmationEvent(String eventType, PaymentConfirmationRequest request,
                                                          String orderId, String fulfillmentId, String reasonCode,
                                                          OffsetDateTime occurredAt, String idempotencyKey) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setAggregateType("PAYMENT_CONFIRMATION");
        event.setAggregateId(request.getProviderEventId());
        event.setExternalTradeNo(request.getExternalTradeNo());
        event.setOrderId(orderId);
        event.setFulfillmentId(fulfillmentId);
        event.setProviderEventId(request.getProviderEventId());
        event.setActorType("CHANNEL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReasonCode(reasonCode);
        event.setPayloadSummaryJson(toJson(buildPaymentPayloadSummary(request, reasonCode)));
        event.setOccurredAt(toLocalDateTime(occurredAt));
        return event;
    }

    private AuditTrailEvent buildPaymentConfirmationRejectedEvent(TicketOrder order,
                                                                  PaymentConfirmationRequest request,
                                                                  String idempotencyKey) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("PAYMENT_CONFIRMATION_REJECTED");
        event.setAggregateType("PAYMENT_CONFIRMATION");
        event.setAggregateId(request.getProviderEventId());
        event.setExternalTradeNo(request.getExternalTradeNo());
        event.setOrderId(order.getOrderId());
        event.setProviderEventId(request.getProviderEventId());
        event.setActorType("CHANNEL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReasonCode("CLOSED_BY_TIMEOUT");
        event.setPayloadSummaryJson(toJson(buildPaymentPayloadSummary(request, "REJECTED")));
        event.setOccurredAt(toLocalDateTime(request.getConfirmedAt()));
        return event;
    }

    private Map<String, Object> buildPaymentPayloadSummary(PaymentConfirmationRequest request, String decision) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("payment_provider", request.getPaymentProvider());
        payloadSummary.put("provider_payment_id", request.getProviderPaymentId());
        payloadSummary.put("decision", decision);
        return payloadSummary;
    }

    private Map<String, Object> buildFulfillmentPayloadSummary(FulfillmentRecord fulfillmentRecord) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("status", fulfillmentRecord.getStatus());
        payloadSummary.put("payment_provider", fulfillmentRecord.getPaymentProvider());
        return payloadSummary;
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime occurredAt) {
        return occurredAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
