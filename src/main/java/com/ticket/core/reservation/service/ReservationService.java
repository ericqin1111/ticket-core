package com.ticket.core.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.service.AuditTrailService;
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
import com.ticket.core.reservation.entity.ReservationRecord;
import com.ticket.core.reservation.mapper.ReservationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final AuditTrailService auditTrailService;

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
        auditTrailService.append(buildReservationCreatedEvent(record, idempotencyKey, now));

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

    public TimeoutReleaseResult releaseConsumedReservationForOrderTimeout(String reservationId,
                                                                         String orderId,
                                                                         String externalTradeNo,
                                                                         LocalDateTime releasedAt) {
        ReservationRecord reservation = reservationRecordMapper.selectById(reservationId);
        if (reservation == null) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if (!"CONSUMED".equals(reservation.getStatus())) {
            throw new IllegalStateException("Timeout release requires CONSUMED reservation: " + reservationId);
        }

        long currentVersion = reservation.getVersion() == null ? 0L : reservation.getVersion();
        int released = reservationRecordMapper.releaseConsumedReservation(reservationId, releasedAt, currentVersion);
        if (released == 0) {
            throw new IllegalStateException("Failed to release consumed reservation: " + reservationId);
        }

        InventoryResource inventory = inventoryService.getActiveInventory(reservation.getInventoryResourceId());
        inventoryService.restoreQuantity(inventory, reservation.getQuantity());

        return new TimeoutReleaseResult(
                orderId,
                reservationId,
                externalTradeNo,
                reservation.getInventoryResourceId(),
                reservation.getQuantity(),
                releasedAt);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private AuditTrailEvent buildReservationCreatedEvent(ReservationRecord record, String idempotencyKey,
                                                         LocalDateTime occurredAt) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("RESERVATION_CREATED");
        event.setAggregateType("RESERVATION");
        event.setAggregateId(record.getReservationId());
        event.setExternalTradeNo(record.getExternalTradeNo());
        event.setReservationId(record.getReservationId());
        event.setInventoryResourceId(record.getInventoryResourceId());
        event.setActorType("CHANNEL");
        event.setIdempotencyKey(idempotencyKey);
        event.setReasonCode("NOT_APPLICABLE");
        event.setPayloadSummaryJson(toJson(buildReservationPayloadSummary(record)));
        event.setOccurredAt(occurredAt);
        return event;
    }

    private Map<String, Object> buildReservationPayloadSummary(ReservationRecord record) {
        Map<String, Object> payloadSummary = new LinkedHashMap<>();
        payloadSummary.put("catalog_item_id", record.getCatalogItemId());
        payloadSummary.put("quantity", record.getQuantity());
        payloadSummary.put("status", record.getStatus());
        return payloadSummary;
    }

    public record TimeoutReleaseResult(
            String orderId,
            String reservationId,
            String externalTradeNo,
            String inventoryResourceId,
            Integer quantity,
            LocalDateTime releasedAt) {
    }
}
