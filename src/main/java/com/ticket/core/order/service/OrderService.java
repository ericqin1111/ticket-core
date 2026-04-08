package com.ticket.core.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.service.AuditTrailService;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.order.dto.CreateOrderRequest;
import com.ticket.core.order.dto.CreateOrderResponse;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import com.ticket.core.reservation.entity.ReservationRecord;
import com.ticket.core.reservation.mapper.ReservationRecordMapper;
import com.ticket.core.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implements @Transactional boundary B (RFC Section 4.1):
 *   - Reservation state validation (ACTIVE, not expired)
 *   - Reservation consume (optimistic lock via MyBatis-Plus @Version)
 *   - Order creation with initial state PENDING_PAYMENT
 * Reservation consume and Order create are always in the same transaction — any failure
 * rolls back both, preventing "Reservation consumed but no Order" orphan state (Failure Scenario 3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String ACTION_NAME = "CREATE_ORDER";

    /** Payment window injected from config; scheduler (out of RFC-01 scope) will enforce the deadline. */
    @Value("${ticket.order.payment-deadline-minutes:30}")
    private int paymentDeadlineMinutes;

    private final TicketOrderMapper ticketOrderMapper;
    private final ReservationRecordMapper reservationRecordMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final AuditTrailService auditTrailService;
    private final ReservationService reservationService;

    @Transactional
    public CreateOrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        String requestHash = idempotencyService.hashRequest(request);

        // Idempotency gate
        IdempotencyRecord idemRecord = idempotencyService.checkAndMarkProcessing(
                ACTION_NAME, idempotencyKey, requestHash, request.getExternalTradeNo());

        if ("SUCCEEDED".equals(idemRecord.getStatus())) {
            log.info("Idempotent replay: action={}, key={}", ACTION_NAME, idempotencyKey);
            return idempotencyService.replayResponse(idemRecord, CreateOrderResponse.class);
        }

        // Reservation validation — Failure Scenario 2: reject CONSUMED or EXPIRED reservations
        ReservationRecord reservation = reservationRecordMapper.selectById(request.getReservationId());
        if (reservation == null) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if ("CONSUMED".equals(reservation.getStatus())) {
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONSUMED);
        }
        if ("EXPIRED".equals(reservation.getStatus()) || LocalDateTime.now().isAfter(reservation.getExpiresAt())) {
            throw new BusinessException(ErrorCode.RESERVATION_EXPIRED);
        }

        // Generate order ID before consuming reservation so both IDs are recorded atomically
        String orderId = UUID.randomUUID().toString();

        // Capture a single timestamp for the entire operation so consumedAt and paymentDeadlineAt
        // are consistent even if processing crosses a second boundary.
        LocalDateTime now = LocalDateTime.now();

        // Consume reservation — MyBatis-Plus @Version adds AND version=? to the UPDATE.
        // If another thread consumed concurrently, affected rows = 0 (Failure Scenario 3 guard).
        reservation.setStatus("CONSUMED");
        reservation.setConsumedOrderId(orderId);
        reservation.setConsumedAt(now);
        int consumed = reservationRecordMapper.updateById(reservation);
        if (consumed == 0) {
            log.warn("Concurrent reservation consume conflict: reservationId={}", request.getReservationId());
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONSUMED);
        }
        log.info("Reservation consumed: reservationId={}, orderId={}", request.getReservationId(), orderId);

        // Create order — initial state is always PENDING_PAYMENT
        TicketOrder order = new TicketOrder();
        order.setOrderId(orderId);
        order.setExternalTradeNo(request.getExternalTradeNo());
        order.setReservationId(request.getReservationId());
        order.setStatus("PENDING_PAYMENT");
        order.setBuyerRef(request.getBuyer().getBuyerRef());
        order.setContactPhone(request.getBuyer().getContactPhone());
        order.setContactEmail(request.getBuyer().getContactEmail());
        order.setPaymentDeadlineAt(now.plusMinutes(paymentDeadlineMinutes));
        if (request.getSubmissionContext() != null) {
            order.setSubmissionContextJson(toJson(request.getSubmissionContext()));
        }
        ticketOrderMapper.insert(order);
        log.info("Order created: orderId={}, externalTradeNo={}", orderId, request.getExternalTradeNo());
        auditTrailService.append(buildReservationConsumedEvent(reservation, request.getExternalTradeNo(), idempotencyKey, now));
        auditTrailService.append(buildOrderCreatedEvent(order, idempotencyKey, now));

        CreateOrderResponse response = CreateOrderResponse.builder()
                .orderId(orderId)
                .externalTradeNo(request.getExternalTradeNo())
                .reservationId(request.getReservationId())
                .status("PENDING_PAYMENT")
                .paymentDeadlineAt(order.getPaymentDeadlineAt().atOffset(ZoneOffset.UTC))
                .build();

        idempotencyService.markSucceeded(idemRecord.getIdempotencyRecordId(), "ORDER", orderId, response);

        return response;
    }

    public List<TicketOrder> findOverduePendingPaymentOrders(LocalDateTime now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        return ticketOrderMapper.selectOverduePendingPaymentOrders(now, limit);
    }

    @Transactional
    public boolean timeoutCloseOrder(String orderId, LocalDateTime now) {
        TicketOrder order = ticketOrderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalStateException("Overdue order candidate not found: " + orderId);
        }
        if (!isClosableByTimeout(order, now)) {
            log.info("Skip timeout close: orderId={}, status={}", orderId, order.getStatus());
            return false;
        }

        long currentVersion = order.getVersion() == null ? 0L : order.getVersion();
        int updated = ticketOrderMapper.closePendingPayment(orderId, now, currentVersion);
        if (updated == 0) {
            return resolveAfterConcurrentTimeoutCloseAttempt(orderId, now);
        }

        order.setStatus("CLOSED");
        order.setClosedAt(now);
        ReservationService.TimeoutReleaseResult releaseResult =
                reservationService.releaseConsumedReservationForOrderTimeout(
                        order.getReservationId(),
                        order.getOrderId(),
                        order.getExternalTradeNo(),
                        now);

        auditTrailService.append(buildOrderTimeoutClosedEvent(order, now));
        auditTrailService.append(buildReservationReleasedEvent(releaseResult));
        auditTrailService.append(buildInventoryRestoredEvent(releaseResult));
        log.info("Timeout close applied: orderId={}, reservationId={}", orderId, order.getReservationId());
        return true;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private AuditTrailEvent buildReservationConsumedEvent(ReservationRecord reservation, String externalTradeNo,
                                                          String idempotencyKey, LocalDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("RESERVATION_CONSUMED");
        event.setAggregateType("RESERVATION");
        event.setAggregateId(reservation.getReservationId());
        event.setExternalTradeNo(externalTradeNo);
        event.setOrderId(reservation.getConsumedOrderId());
        event.setReservationId(reservation.getReservationId());
        event.setInventoryResourceId(reservation.getInventoryResourceId());
        event.setActorType("CHANNEL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReasonCode("NOT_APPLICABLE");
        event.setPayloadSummaryJson(toJson(buildReservationConsumedPayloadSummary(reservation)));
        event.setOccurredAt(occurredAt);
        return event;
    }

    private AuditTrailEvent buildOrderCreatedEvent(TicketOrder order, String idempotencyKey, LocalDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("ORDER_CREATED");
        event.setAggregateType("ORDER");
        event.setAggregateId(order.getOrderId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(order.getOrderId());
        event.setReservationId(order.getReservationId());
        event.setActorType("CHANNEL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReasonCode("NOT_APPLICABLE");
        event.setPayloadSummaryJson(toJson(buildOrderCreatedPayloadSummary(order)));
        event.setOccurredAt(occurredAt);
        return event;
    }

    private Map<String, Object> buildReservationConsumedPayloadSummary(ReservationRecord reservation) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("quantity", reservation.getQuantity());
        payloadSummary.put("status", reservation.getStatus());
        return payloadSummary;
    }

    private Map<String, Object> buildOrderCreatedPayloadSummary(TicketOrder order) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("status", order.getStatus());
        payloadSummary.put("buyer_ref", order.getBuyerRef());
        payloadSummary.put("payment_deadline_at", order.getPaymentDeadlineAt().atOffset(ZoneOffset.UTC).toString());
        return payloadSummary;
    }

    private boolean isClosableByTimeout(TicketOrder order, LocalDateTime now) {
        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            return false;
        }
        if (order.getPaymentDeadlineAt() == null) {
            log.warn("Skip timeout close due to missing payment deadline: orderId={}", order.getOrderId());
            return false;
        }
        return !order.getPaymentDeadlineAt().isAfter(now);
    }

    private boolean resolveAfterConcurrentTimeoutCloseAttempt(String orderId, LocalDateTime now) {
        TicketOrder latestOrder = ticketOrderMapper.selectById(orderId);
        if (latestOrder == null) {
            throw new IllegalStateException("Overdue order candidate disappeared after close conflict: " + orderId);
        }
        if (!isClosableByTimeout(latestOrder, now)) {
            log.info("Skip timeout close after concurrent update: orderId={}, status={}",
                    orderId, latestOrder.getStatus());
            return false;
        }
        log.warn("Timeout close lost concurrent update race while order remains overdue: orderId={}", orderId);
        return false;
    }

    private AuditTrailEvent buildOrderTimeoutClosedEvent(TicketOrder order, LocalDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("ORDER_TIMEOUT_CLOSED");
        event.setAggregateType("ORDER");
        event.setAggregateId(order.getOrderId());
        event.setExternalTradeNo(order.getExternalTradeNo());
        event.setOrderId(order.getOrderId());
        event.setReservationId(order.getReservationId());
        event.setActorType("SYSTEM");
        event.setReasonCode("ORDER_PAYMENT_TIMEOUT");
        event.setPayloadSummaryJson(toJson(buildOrderTimeoutPayloadSummary(order)));
        event.setOccurredAt(occurredAt);
        return event;
    }

    private AuditTrailEvent buildReservationReleasedEvent(ReservationService.TimeoutReleaseResult releaseResult) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("RESERVATION_RELEASED");
        event.setAggregateType("RESERVATION");
        event.setAggregateId(releaseResult.reservationId());
        event.setExternalTradeNo(releaseResult.externalTradeNo());
        event.setOrderId(releaseResult.orderId());
        event.setReservationId(releaseResult.reservationId());
        event.setInventoryResourceId(releaseResult.inventoryResourceId());
        event.setActorType("SYSTEM");
        event.setReasonCode("ORDER_PAYMENT_TIMEOUT");
        event.setPayloadSummaryJson(toJson(buildReservationReleasedPayloadSummary(releaseResult)));
        event.setOccurredAt(releaseResult.releasedAt());
        return event;
    }

    private AuditTrailEvent buildInventoryRestoredEvent(ReservationService.TimeoutReleaseResult releaseResult) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("INVENTORY_RESTORED");
        event.setAggregateType("INVENTORY_RESOURCE");
        event.setAggregateId(releaseResult.inventoryResourceId());
        event.setExternalTradeNo(releaseResult.externalTradeNo());
        event.setOrderId(releaseResult.orderId());
        event.setReservationId(releaseResult.reservationId());
        event.setInventoryResourceId(releaseResult.inventoryResourceId());
        event.setActorType("SYSTEM");
        event.setReasonCode("ORDER_PAYMENT_TIMEOUT");
        event.setPayloadSummaryJson(toJson(buildInventoryRestoredPayloadSummary(releaseResult)));
        event.setOccurredAt(releaseResult.releasedAt());
        return event;
    }

    private Map<String, Object> buildOrderTimeoutPayloadSummary(TicketOrder order) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("status", order.getStatus());
        payloadSummary.put("payment_deadline_at", order.getPaymentDeadlineAt().atOffset(ZoneOffset.UTC).toString());
        payloadSummary.put("closed_at", order.getClosedAt().atOffset(ZoneOffset.UTC).toString());
        return payloadSummary;
    }

    private Map<String, Object> buildReservationReleasedPayloadSummary(ReservationService.TimeoutReleaseResult releaseResult) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("quantity", releaseResult.quantity());
        payloadSummary.put("released_at", releaseResult.releasedAt().atOffset(ZoneOffset.UTC).toString());
        return payloadSummary;
    }

    private Map<String, Object> buildInventoryRestoredPayloadSummary(ReservationService.TimeoutReleaseResult releaseResult) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("quantity", releaseResult.quantity());
        payloadSummary.put("inventory_resource_id", releaseResult.inventoryResourceId());
        return payloadSummary;
    }
}
