package com.ticket.core.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.order.dto.BuyerDto;
import com.ticket.core.order.dto.CreateOrderRequest;
import com.ticket.core.order.dto.CreateOrderResponse;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import com.ticket.core.reservation.entity.ReservationRecord;
import com.ticket.core.reservation.mapper.ReservationRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private TicketOrderMapper ticketOrderMapper;
    @Mock private ReservationRecordMapper reservationRecordMapper;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private OrderService orderService;

    private static final String ACTION_NAME = "CREATE_ORDER";
    private static final String IDEMPOTENCY_KEY = "order-idem-key-001";
    private static final String RESERVATION_ID = "res-001";
    private static final String EXTERNAL_TRADE_NO = "trade-20260407-001";

    private CreateOrderRequest buildRequest() {
        BuyerDto buyer = new BuyerDto();
        buyer.setBuyerRef("buyer-ref-001");
        buyer.setContactPhone("13800138000");

        CreateOrderRequest req = new CreateOrderRequest();
        req.setExternalTradeNo(EXTERNAL_TRADE_NO);
        req.setReservationId(RESERVATION_ID);
        req.setBuyer(buyer);
        return req;
    }

    private IdempotencyRecord processingRecord() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId("order-idem-record-id-001");
        record.setStatus("PROCESSING");
        return record;
    }

    private ReservationRecord activeReservation() {
        ReservationRecord r = new ReservationRecord();
        r.setReservationId(RESERVATION_ID);
        r.setStatus("ACTIVE");
        r.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        r.setQuantity(2);
        r.setVersion(0L);
        return r;
    }

    @Test
    void createOrder_happyPath_returnsOrderInPendingPayment() {
        CreateOrderRequest request = buildRequest();
        when(idempotencyService.hashRequest(request)).thenReturn("hash-order-001");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-order-001", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(reservationRecordMapper.selectById(RESERVATION_ID)).thenReturn(activeReservation());
        when(reservationRecordMapper.updateById(any(ReservationRecord.class))).thenReturn(1);
        when(ticketOrderMapper.insert(any(TicketOrder.class))).thenReturn(1);
        doNothing().when(idempotencyService).markSucceeded(anyString(), eq("ORDER"), anyString(), any(CreateOrderResponse.class));

        CreateOrderResponse response = orderService.createOrder(IDEMPOTENCY_KEY, request);

        assertThat(response.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(response.getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.getExternalTradeNo()).isEqualTo(EXTERNAL_TRADE_NO);
        assertThat(response.getOrderId()).isNotBlank();
        assertThat(response.getPaymentDeadlineAt()).isNotNull();

        verify(reservationRecordMapper).updateById(argThat((ReservationRecord r) ->
                "CONSUMED".equals(r.getStatus()) && RESERVATION_ID.equals(r.getReservationId())));
        verify(ticketOrderMapper).insert(argThat((TicketOrder o) ->
                "PENDING_PAYMENT".equals(o.getStatus()) && RESERVATION_ID.equals(o.getReservationId())));
    }

    @Test
    void createOrder_reservationAlreadyConsumed_throwsBusinessException() {
        CreateOrderRequest request = buildRequest();
        ReservationRecord consumed = activeReservation();
        consumed.setStatus("CONSUMED");

        when(idempotencyService.hashRequest(request)).thenReturn("hash-order-002");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-order-002", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(reservationRecordMapper.selectById(RESERVATION_ID)).thenReturn(consumed);

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESERVATION_ALREADY_CONSUMED);

        verify(ticketOrderMapper, never()).insert(any(TicketOrder.class));
    }

    @Test
    void createOrder_reservationExpired_throwsBusinessException() {
        CreateOrderRequest request = buildRequest();
        ReservationRecord expired = activeReservation();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(idempotencyService.hashRequest(request)).thenReturn("hash-order-003");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-order-003", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(reservationRecordMapper.selectById(RESERVATION_ID)).thenReturn(expired);

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESERVATION_EXPIRED);

        verify(ticketOrderMapper, never()).insert(any(TicketOrder.class));
    }

    @Test
    void createOrder_concurrentConsume_throwsBusinessException() {
        CreateOrderRequest request = buildRequest();
        when(idempotencyService.hashRequest(request)).thenReturn("hash-order-004");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-order-004", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(reservationRecordMapper.selectById(RESERVATION_ID)).thenReturn(activeReservation());
        when(reservationRecordMapper.updateById(any(ReservationRecord.class))).thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(IDEMPOTENCY_KEY, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESERVATION_ALREADY_CONSUMED);

        verify(ticketOrderMapper, never()).insert(any(TicketOrder.class));
    }
}
