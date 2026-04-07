package com.ticket.core.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    /** Default payment window; scheduler (out of RFC-01 scope) will enforce the deadline. */
    private static final int PAYMENT_DEADLINE_MINUTES = 30;

    private final TicketOrderMapper ticketOrderMapper;
    private final ReservationRecordMapper reservationRecordMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

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

        // Consume reservation — MyBatis-Plus @Version adds AND version=? to the UPDATE.
        // If another thread consumed concurrently, affected rows = 0 (Failure Scenario 3 guard).
        reservation.setStatus("CONSUMED");
        reservation.setConsumedOrderId(orderId);
        reservation.setConsumedAt(LocalDateTime.now());
        int consumed = reservationRecordMapper.updateById(reservation);
        if (consumed == 0) {
            log.warn("Concurrent reservation consume conflict: reservationId={}", request.getReservationId());
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CONSUMED);
        }
        log.info("Reservation consumed: reservationId={}, orderId={}", request.getReservationId(), orderId);

        // Create order — initial state is always PENDING_PAYMENT
        LocalDateTime now = LocalDateTime.now();
        TicketOrder order = new TicketOrder();
        order.setOrderId(orderId);
        order.setExternalTradeNo(request.getExternalTradeNo());
        order.setReservationId(request.getReservationId());
        order.setStatus("PENDING_PAYMENT");
        order.setBuyerRef(request.getBuyer().getBuyerRef());
        order.setContactPhone(request.getBuyer().getContactPhone());
        order.setContactEmail(request.getBuyer().getContactEmail());
        order.setPaymentDeadlineAt(now.plusMinutes(PAYMENT_DEADLINE_MINUTES));
        if (request.getSubmissionContext() != null) {
            order.setSubmissionContextJson(toJson(request.getSubmissionContext()));
        }
        ticketOrderMapper.insert(order);
        log.info("Order created: orderId={}, externalTradeNo={}", orderId, request.getExternalTradeNo());

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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
