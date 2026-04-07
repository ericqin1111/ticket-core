package com.ticket.core.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import com.ticket.core.fulfillment.service.FulfillmentService;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.mapper.IdempotencyRecordMapper;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import com.ticket.core.payment.dto.PaymentConfirmationRequest;
import com.ticket.core.payment.dto.PaymentConfirmationResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentConfirmationServiceTest {

    @Test
    void confirmPayment_happyPath_confirmsOrderCreatesFulfillmentAndCachesAppliedResponse() {
        PaymentConfirmationRequest request = buildRequest();
        TicketOrder order = pendingOrder();
        AtomicReference<String> selectedTradeNo = new AtomicReference<>();
        AtomicReference<String> confirmedOrderId = new AtomicReference<>();
        AtomicReference<LocalDateTime> confirmedAt = new AtomicReference<>();
        AtomicReference<Long> confirmedVersion = new AtomicReference<>();
        TicketOrderMapper ticketOrderMapper = ticketOrderMapperProxy(
                order, 1, selectedTradeNo, confirmedOrderId, confirmedAt, confirmedVersion);
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        RecordingFulfillmentService fulfillmentService = new RecordingFulfillmentService();
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapper, idempotencyService, fulfillmentService, new ObjectMapper());

        PaymentConfirmationResponse response = service.confirmPayment("idem-key-001", request);

        assertThat(response.getOrderId()).isEqualTo(order.getOrderId());
        assertThat(response.getExternalTradeNo()).isEqualTo(order.getExternalTradeNo());
        assertThat(response.getOrderStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getFulfillmentStatus()).isEqualTo("PENDING");
        assertThat(response.getPaymentConfirmationStatus()).isEqualTo("APPLIED");
        assertThat(response.getFulfillmentId()).isNotBlank();
        assertThat(response.getConfirmedAt()).isEqualTo(request.getConfirmedAt().withOffsetSameInstant(ZoneOffset.UTC));

        assertThat(idempotencyService.lastActionName).isEqualTo("PAYMENT_CONFIRMATION");
        assertThat(idempotencyService.lastIdempotencyKey).isEqualTo("idem-key-001");
        assertThat(idempotencyService.lastExternalTradeNo).isEqualTo(order.getExternalTradeNo());
        assertThat(idempotencyService.markedResourceType).isEqualTo("FULFILLMENT");
        assertThat(idempotencyService.markedResourceId).isEqualTo(response.getFulfillmentId());
        assertThat(((PaymentConfirmationResponse) idempotencyService.markedResponseBody).getPaymentConfirmationStatus())
                .isEqualTo("APPLIED");

        assertThat(selectedTradeNo.get()).isEqualTo(order.getExternalTradeNo());
        assertThat(confirmedOrderId.get()).isEqualTo(order.getOrderId());
        assertThat(confirmedVersion.get()).isEqualTo(order.getVersion());
        assertThat(confirmedAt.get()).isEqualTo(LocalDateTime.of(2026, 4, 7, 8, 30));

        assertThat(fulfillmentService.createdRecord).isNotNull();
        assertThat(fulfillmentService.createdRecord.getOrderId()).isEqualTo(order.getOrderId());
        assertThat(fulfillmentService.createdRecord.getStatus()).isEqualTo("PENDING");
        assertThat(fulfillmentService.createdRecord.getPaymentProvider()).isEqualTo(request.getPaymentProvider());
        assertThat(fulfillmentService.createdRecord.getProviderEventId()).isEqualTo(request.getProviderEventId());
        assertThat(fulfillmentService.createdRecord.getProviderPaymentId()).isEqualTo(request.getProviderPaymentId());
        assertThat(fulfillmentService.createdRecord.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 4, 7, 8, 30));
        assertThat(fulfillmentService.createdRecord.getChannelContextJson())
                .isEqualTo("{\"channel\":\"wechat\",\"traceId\":\"trace-001\"}");
    }

    @Test
    void confirmPayment_whenOrderConfirmUpdateFails_throwsAndDoesNotCreateFulfillment() {
        PaymentConfirmationRequest request = buildRequest();
        TicketOrderMapper ticketOrderMapper = ticketOrderMapperProxy(
                pendingOrder(), 0, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        RecordingFulfillmentService fulfillmentService = new RecordingFulfillmentService();
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapper, idempotencyService, fulfillmentService, new ObjectMapper());

        assertThatThrownBy(() -> service.confirmPayment("idem-key-002", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_CONFIRMATION_IN_PROGRESS);

        assertThat(fulfillmentService.createdRecord).isNull();
        assertThat(idempotencyService.markedResourceId).isNull();
    }

    @Test
    void confirmPayment_sameIdempotencyKeySucceeded_replaysCachedResponse() {
        PaymentConfirmationResponse cached = PaymentConfirmationResponse.builder()
                .orderId("order-confirm-001")
                .externalTradeNo("trade-confirm-001")
                .orderStatus("CONFIRMED")
                .fulfillmentId("ful-existing-001")
                .fulfillmentStatus("PENDING")
                .paymentConfirmationStatus("APPLIED")
                .confirmedAt(OffsetDateTime.parse("2026-04-07T08:30:00Z"))
                .build();
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(succeededRecord());
        idempotencyService.replayedResponse = cached;
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapperProxy(null, 0, new AtomicReference<>(), new AtomicReference<>(),
                        new AtomicReference<>(), new AtomicReference<>()),
                idempotencyService,
                new RecordingFulfillmentService(),
                new ObjectMapper());

        PaymentConfirmationResponse response = service.confirmPayment("idem-key-003", buildRequest());

        assertThat(response.getPaymentConfirmationStatus()).isEqualTo("REPLAYED");
        assertThat(response.getFulfillmentId()).isEqualTo("ful-existing-001");
        assertThat(idempotencyService.markedResourceId).isNull();
    }

    @Test
    void confirmPayment_differentKeyForConfirmedOrder_returnsReplayedAndCachesStableResult() {
        TicketOrderMapper ticketOrderMapper = ticketOrderMapperProxy(
                confirmedOrder(), 0, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        RecordingFulfillmentService fulfillmentService = new RecordingFulfillmentService();
        fulfillmentService.foundRecord = existingFulfillment();
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapper, idempotencyService, fulfillmentService, new ObjectMapper());

        PaymentConfirmationResponse response = service.confirmPayment("idem-key-004", buildRequest());

        assertThat(response.getPaymentConfirmationStatus()).isEqualTo("REPLAYED");
        assertThat(response.getFulfillmentId()).isEqualTo("ful-existing-001");
        assertThat(idempotencyService.markedResourceType).isEqualTo("FULFILLMENT");
        assertThat(idempotencyService.markedResourceId).isEqualTo("ful-existing-001");
    }

    @Test
    void confirmPayment_processingDuplicate_returnsInProgress() {
        IdempotencyRecord inProgress = processingRecord();
        inProgress.setNewlyCreated(false);
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(inProgress);
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapperProxy(pendingOrder(), 1, new AtomicReference<>(), new AtomicReference<>(),
                        new AtomicReference<>(), new AtomicReference<>()),
                idempotencyService,
                new RecordingFulfillmentService(),
                new ObjectMapper());

        assertThatThrownBy(() -> service.confirmPayment("idem-key-005", buildRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_CONFIRMATION_IN_PROGRESS);
    }

    @Test
    void confirmPayment_orderNotFound_returnsBusinessException() {
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapperProxy(null, 0, new AtomicReference<>(), new AtomicReference<>(),
                        new AtomicReference<>(), new AtomicReference<>()),
                idempotencyService,
                new RecordingFulfillmentService(),
                new ObjectMapper());

        assertThatThrownBy(() -> service.confirmPayment("idem-key-006", buildRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void confirmPayment_orderClosed_returnsOrderNotConfirmable() {
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapperProxy(closedOrder(), 0, new AtomicReference<>(), new AtomicReference<>(),
                        new AtomicReference<>(), new AtomicReference<>()),
                idempotencyService,
                new RecordingFulfillmentService(),
                new ObjectMapper());

        assertThatThrownBy(() -> service.confirmPayment("idem-key-007", buildRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_CONFIRMABLE);
    }

    @Test
    void confirmPayment_confirmedOrderWithoutFulfillment_returnsInvariantBroken() {
        RecordingIdempotencyService idempotencyService = new RecordingIdempotencyService(processingRecord());
        RecordingFulfillmentService fulfillmentService = new RecordingFulfillmentService();
        PaymentConfirmationService service = new PaymentConfirmationService(
                ticketOrderMapperProxy(confirmedOrder(), 0, new AtomicReference<>(), new AtomicReference<>(),
                        new AtomicReference<>(), new AtomicReference<>()),
                idempotencyService,
                fulfillmentService,
                new ObjectMapper());

        assertThatThrownBy(() -> service.confirmPayment("idem-key-008", buildRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FULFILLMENT_INVARIANT_BROKEN);
    }

    private PaymentConfirmationRequest buildRequest() {
        PaymentConfirmationRequest request = new PaymentConfirmationRequest();
        request.setExternalTradeNo("trade-confirm-001");
        request.setPaymentProvider("WECHAT_PAY");
        request.setProviderEventId("evt-001");
        request.setProviderPaymentId("pay-001");
        request.setConfirmedAt(OffsetDateTime.parse("2026-04-07T16:30:00+08:00"));
        Map<String, String> channelContext = new LinkedHashMap<>();
        channelContext.put("channel", "wechat");
        channelContext.put("traceId", "trace-001");
        request.setChannelContext(channelContext);
        return request;
    }

    private TicketOrder pendingOrder() {
        TicketOrder order = new TicketOrder();
        order.setOrderId("order-confirm-001");
        order.setExternalTradeNo("trade-confirm-001");
        order.setStatus("PENDING_PAYMENT");
        order.setVersion(3L);
        return order;
    }

    private TicketOrder confirmedOrder() {
        TicketOrder order = pendingOrder();
        order.setStatus("CONFIRMED");
        order.setConfirmedAt(LocalDateTime.of(2026, 4, 7, 8, 30));
        return order;
    }

    private TicketOrder closedOrder() {
        TicketOrder order = pendingOrder();
        order.setStatus("CLOSED");
        return order;
    }

    private IdempotencyRecord processingRecord() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId("idem-record-001");
        record.setStatus("PROCESSING");
        record.setNewlyCreated(true);
        return record;
    }

    private IdempotencyRecord succeededRecord() {
        IdempotencyRecord record = processingRecord();
        record.setStatus("SUCCEEDED");
        record.setNewlyCreated(false);
        return record;
    }

    private FulfillmentRecord existingFulfillment() {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId("ful-existing-001");
        record.setOrderId("order-confirm-001");
        record.setStatus("PENDING");
        record.setConfirmedAt(LocalDateTime.of(2026, 4, 7, 8, 30));
        return record;
    }

    private TicketOrderMapper ticketOrderMapperProxy(TicketOrder selectedOrder,
                                                     int confirmResult,
                                                     AtomicReference<String> selectedTradeNo,
                                                     AtomicReference<String> confirmedOrderId,
                                                     AtomicReference<LocalDateTime> confirmedAt,
                                                     AtomicReference<Long> confirmedVersion) {
        return (TicketOrderMapper) Proxy.newProxyInstance(
                TicketOrderMapper.class.getClassLoader(),
                new Class[]{TicketOrderMapper.class},
                (proxy, method, args) -> {
                    if ("selectByExternalTradeNo".equals(method.getName())) {
                        selectedTradeNo.set((String) args[0]);
                        return selectedOrder;
                    }
                    if ("confirmPendingPayment".equals(method.getName())) {
                        confirmedOrderId.set((String) args[0]);
                        confirmedAt.set((LocalDateTime) args[1]);
                        confirmedVersion.set((Long) args[2]);
                        return confirmResult;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(method.getName())) {
                        return "TicketOrderMapperProxy";
                    }
                    return null;
                });
    }

    private static final class RecordingIdempotencyService extends IdempotencyService {

        private final IdempotencyRecord recordToReturn;
        private String lastActionName;
        private String lastIdempotencyKey;
        private String lastExternalTradeNo;
        private String markedResourceType;
        private String markedResourceId;
        private Object markedResponseBody;
        private PaymentConfirmationResponse replayedResponse;

        private RecordingIdempotencyService(IdempotencyRecord recordToReturn) {
            super(dummyIdempotencyMapper(), new ObjectMapper());
            this.recordToReturn = recordToReturn;
        }

        @Override
        public String hashRequest(Object request) {
            return "payment-confirmation-hash";
        }

        @Override
        public IdempotencyRecord checkAndMarkProcessing(String actionName, String idempotencyKey,
                                                        String requestHash, String externalTradeNo) {
            this.lastActionName = actionName;
            this.lastIdempotencyKey = idempotencyKey;
            this.lastExternalTradeNo = externalTradeNo;
            return recordToReturn;
        }

        @Override
        public void markSucceeded(String idempotencyRecordId, String resourceType,
                                  String resourceId, Object responseBody) {
            this.markedResourceType = resourceType;
            this.markedResourceId = resourceId;
            this.markedResponseBody = responseBody;
        }

        @Override
        public <T> T replayResponse(IdempotencyRecord record, Class<T> responseClass) {
            return responseClass.cast(replayedResponse);
        }

        private static IdempotencyRecordMapper dummyIdempotencyMapper() {
            return (IdempotencyRecordMapper) Proxy.newProxyInstance(
                    IdempotencyRecordMapper.class.getClassLoader(),
                    new Class[]{IdempotencyRecordMapper.class},
                    (proxy, method, args) -> null);
        }
    }

    private static final class RecordingFulfillmentService extends FulfillmentService {

        private FulfillmentRecord createdRecord;
        private FulfillmentRecord foundRecord;

        private RecordingFulfillmentService() {
            super(dummyFulfillmentMapper());
        }

        @Override
        public void create(FulfillmentRecord fulfillmentRecord) {
            this.createdRecord = fulfillmentRecord;
        }

        @Override
        public FulfillmentRecord findByOrderId(String orderId) {
            return foundRecord;
        }

        private static FulfillmentRecordMapper dummyFulfillmentMapper() {
            return (FulfillmentRecordMapper) Proxy.newProxyInstance(
                    FulfillmentRecordMapper.class.getClassLoader(),
                    new Class[]{FulfillmentRecordMapper.class},
                    (proxy, method, args) -> null);
        }
    }
}
