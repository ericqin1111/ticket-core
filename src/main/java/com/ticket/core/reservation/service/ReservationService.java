package com.ticket.core.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.catalog.entity.CatalogItem;
import com.ticket.core.catalog.service.CatalogService;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.service.IdempotencyService;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.inventory.service.InventoryService;
import com.ticket.core.reservation.dto.CreateReservationRequest;
import com.ticket.core.reservation.dto.CreateReservationResponse;
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
 * Implements @Transactional boundary A (RFC Section 4.1):
 *   - Catalog item validation (binding check)
 *   - Inventory lock via atomic conditional UPDATE (strong consistency)
 *   - Reservation record creation with TTL
 * Any failure rolls back the entire boundary atomically, ensuring no half-valid Reservation is created.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String ACTION_NAME = "CREATE_RESERVATION";

    private final ReservationRecordMapper reservationRecordMapper;
    private final CatalogService catalogService;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateReservationResponse createReservation(String idempotencyKey, CreateReservationRequest request) {
        String requestHash = idempotencyService.hashRequest(request);

        // Idempotency gate — returns existing record (PROCESSING or SUCCEEDED) or newly inserted PROCESSING record
        IdempotencyRecord idemRecord = idempotencyService.checkAndMarkProcessing(
                ACTION_NAME, idempotencyKey, requestHash, request.getExternalTradeNo());

        if ("SUCCEEDED".equals(idemRecord.getStatus())) {
            log.info("Idempotent replay: action={}, key={}", ACTION_NAME, idempotencyKey);
            return idempotencyService.replayResponse(idemRecord, CreateReservationResponse.class);
        }

        // Catalog item validation — ensures binding to an active inventory resource exists
        CatalogItem catalogItem = catalogService.validateAndGetActiveItem(request.getCatalogItemId());

        // Inventory lock — atomic conditional UPDATE, guards against oversell (Failure Scenario 1)
        InventoryResource inventory = inventoryService.getActiveInventory(catalogItem.getInventoryResourceId());
        inventoryService.lockQuantity(inventory, request.getQuantity());

        // Build and persist Reservation
        LocalDateTime now = LocalDateTime.now();
        String reservationId = UUID.randomUUID().toString();
        ReservationRecord record = new ReservationRecord();
        record.setReservationId(reservationId);
        record.setExternalTradeNo(request.getExternalTradeNo());
        record.setCatalogItemId(request.getCatalogItemId());
        record.setInventoryResourceId(catalogItem.getInventoryResourceId());
        record.setQuantity(request.getQuantity());
        record.setStatus("ACTIVE");
        record.setExpiresAt(now.plusSeconds(request.getReservationTtlSeconds()));
        record.setVersion(0L);
        if (request.getChannelContext() != null) {
            record.setChannelContextJson(toJson(request.getChannelContext()));
        }
        reservationRecordMapper.insert(record);
        log.info("Reservation created: reservationId={}, externalTradeNo={}",
                reservationId, request.getExternalTradeNo());

        CreateReservationResponse response = CreateReservationResponse.builder()
                .reservationId(reservationId)
                .externalTradeNo(request.getExternalTradeNo())
                .catalogItemId(request.getCatalogItemId())
                .quantity(request.getQuantity())
                .status("ACTIVE")
                .expiresAt(record.getExpiresAt().atOffset(ZoneOffset.UTC))
                .build();

        // Cache result — stored within the same transaction, rolled back if anything fails
        idempotencyService.markSucceeded(idemRecord.getIdempotencyRecordId(), "RESERVATION", reservationId, response);

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
