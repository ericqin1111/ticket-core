package com.ticket.core.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.catalog.entity.CatalogItem;
import com.ticket.core.catalog.service.CatalogService;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.inventory.service.InventoryService;
import com.ticket.core.reservation.dto.CreateReservationRequest;
import com.ticket.core.reservation.dto.CreateReservationResponse;
import com.ticket.core.reservation.mapper.ReservationRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private ReservationRecordMapper reservationRecordMapper;
    @Mock private CatalogService catalogService;
    @Mock private InventoryService inventoryService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ReservationService reservationService;

    private static final String IDEMPOTENCY_KEY = "test-idem-key-001";
    private static final String CATALOG_ITEM_ID = "item-001";
    private static final String INVENTORY_RESOURCE_ID = "inv-001";
    private static final String EXTERNAL_TRADE_NO = "trade-20260407-001";

    private CreateReservationRequest buildRequest() {
        CreateReservationRequest req = new CreateReservationRequest();
        req.setExternalTradeNo(EXTERNAL_TRADE_NO);
        req.setCatalogItemId(CATALOG_ITEM_ID);
        req.setQuantity(2);
        req.setReservationTtlSeconds(300);
        return req;
    }

    private IdempotencyRecord processingRecord() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordId("idem-record-id-001");
        record.setStatus("PROCESSING");
        return record;
    }

    private CatalogItem activeCatalogItem() {
        CatalogItem item = new CatalogItem();
        item.setCatalogItemId(CATALOG_ITEM_ID);
        item.setInventoryResourceId(INVENTORY_RESOURCE_ID);
        item.setStatus("ACTIVE");
        return item;
    }

    private InventoryResource activeInventory() {
        InventoryResource inv = new InventoryResource();
        inv.setInventoryResourceId(INVENTORY_RESOURCE_ID);
        inv.setTotalQuantity(100);
        inv.setReservedQuantity(10);
        inv.setStatus("ACTIVE");
        inv.setVersion(0L);
        return inv;
    }

    @Test
    void createReservation_happyPath_returnsActiveReservation() {
        CreateReservationRequest request = buildRequest();
        when(idempotencyService.hashRequest(request)).thenReturn("hash-001");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-001", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(catalogService.validateAndGetActiveItem(CATALOG_ITEM_ID)).thenReturn(activeCatalogItem());
        when(inventoryService.getActiveInventory(INVENTORY_RESOURCE_ID)).thenReturn(activeInventory());
        doNothing().when(inventoryService).lockQuantity(any(), eq(2));
        when(reservationRecordMapper.insert(any())).thenReturn(1);
        doNothing().when(idempotencyService).markSucceeded(anyString(), eq("RESERVATION"), anyString(), any());

        CreateReservationResponse response = reservationService.createReservation(IDEMPOTENCY_KEY, request);

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getCatalogItemId()).isEqualTo(CATALOG_ITEM_ID);
        assertThat(response.getQuantity()).isEqualTo(2);
        assertThat(response.getExternalTradeNo()).isEqualTo(EXTERNAL_TRADE_NO);
        assertThat(response.getReservationId()).isNotBlank();
        assertThat(response.getExpiresAt()).isNotNull();

        verify(reservationRecordMapper).insert(any());
        verify(idempotencyService).markSucceeded(eq("idem-record-id-001"), eq("RESERVATION"), anyString(), any());
    }

    @Test
    void createReservation_insufficientInventory_throwsBusinessException() {
        CreateReservationRequest request = buildRequest();
        when(idempotencyService.hashRequest(request)).thenReturn("hash-002");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-002", EXTERNAL_TRADE_NO))
                .thenReturn(processingRecord());
        when(catalogService.validateAndGetActiveItem(CATALOG_ITEM_ID)).thenReturn(activeCatalogItem());
        when(inventoryService.getActiveInventory(INVENTORY_RESOURCE_ID)).thenReturn(activeInventory());
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY))
                .when(inventoryService).lockQuantity(any(), eq(2));

        assertThatThrownBy(() -> reservationService.createReservation(IDEMPOTENCY_KEY, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY);

        verify(reservationRecordMapper, never()).insert(any());
        verify(idempotencyService, never()).markSucceeded(anyString(), anyString(), anyString(), any());
    }

    @Test
    void createReservation_idempotentReplay_returnsCachedResponse() throws Exception {
        CreateReservationRequest request = buildRequest();
        String cachedJson = "{\"reservationId\":\"cached-id\",\"status\":\"ACTIVE\"}";
        IdempotencyRecord succeededRecord = new IdempotencyRecord();
        succeededRecord.setIdempotencyRecordId("idem-record-id-001");
        succeededRecord.setStatus("SUCCEEDED");
        succeededRecord.setResponsePayload(cachedJson);
        CreateReservationResponse cachedResponse = CreateReservationResponse.builder()
                .reservationId("cached-id").status("ACTIVE").build();

        when(idempotencyService.hashRequest(request)).thenReturn("hash-003");
        when(idempotencyService.checkAndMarkProcessing(ACTION_NAME, IDEMPOTENCY_KEY, "hash-003", EXTERNAL_TRADE_NO))
                .thenReturn(succeededRecord);
        when(idempotencyService.replayResponse(succeededRecord, CreateReservationResponse.class))
                .thenReturn(cachedResponse);

        CreateReservationResponse response = reservationService.createReservation(IDEMPOTENCY_KEY, request);

        assertThat(response.getReservationId()).isEqualTo("cached-id");
        verify(catalogService, never()).validateAndGetActiveItem(anyString());
        verify(reservationRecordMapper, never()).insert(any());
    }

    private static final String ACTION_NAME = "CREATE_RESERVATION";
}
