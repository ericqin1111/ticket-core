package com.ticket.core.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticket.core.catalog.entity.CatalogItem;
import com.ticket.core.catalog.mapper.CatalogItemMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.mapper.AuditTrailEventMapper;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import com.ticket.core.idempotency.mapper.IdempotencyRecordMapper;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.inventory.mapper.InventoryResourceMapper;
import com.ticket.core.order.entity.TicketOrder;
import com.ticket.core.order.mapper.TicketOrderMapper;
import com.ticket.core.order.service.OrderTimeoutSweepScheduler;
import com.ticket.core.reservation.entity.ReservationRecord;
import com.ticket.core.reservation.mapper.ReservationRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

/**
 * Shared base for all integration tests.
 * Spins up a real MySQL 8 container via Testcontainers, runs Flyway migrations,
 * and wires a random-port Spring Boot server. Each test starts with a clean database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected CatalogItemMapper catalogItemMapper;
    @Autowired
    protected InventoryResourceMapper inventoryResourceMapper;
    @Autowired
    protected ReservationRecordMapper reservationRecordMapper;
    @Autowired
    protected TicketOrderMapper ticketOrderMapper;
    @Autowired
    protected AuditTrailEventMapper auditTrailEventMapper;
    @Autowired
    protected FulfillmentRecordMapper fulfillmentRecordMapper;
    @Autowired
    protected IdempotencyRecordMapper idempotencyRecordMapper;
    @Autowired
    protected OrderTimeoutSweepScheduler orderTimeoutSweepScheduler;

    /**
     * Truncate all domain tables before each test to guarantee isolation.
     * Order matters: FK-like logical dependencies (order→reservation→inventory→catalog).
     */
    @BeforeEach
    void truncateAllTables() {
        auditTrailEventMapper.delete(new LambdaQueryWrapper<AuditTrailEvent>().isNotNull(AuditTrailEvent::getEventId));
        fulfillmentRecordMapper.delete(new LambdaQueryWrapper<FulfillmentRecord>().isNotNull(FulfillmentRecord::getFulfillmentId));
        ticketOrderMapper.delete(new LambdaQueryWrapper<TicketOrder>().isNotNull(TicketOrder::getOrderId));
        reservationRecordMapper.delete(new LambdaQueryWrapper<ReservationRecord>().isNotNull(ReservationRecord::getReservationId));
        idempotencyRecordMapper.delete(new LambdaQueryWrapper<IdempotencyRecord>().isNotNull(IdempotencyRecord::getIdempotencyRecordId));
        inventoryResourceMapper.delete(new LambdaQueryWrapper<InventoryResource>().isNotNull(InventoryResource::getInventoryResourceId));
        catalogItemMapper.delete(new LambdaQueryWrapper<CatalogItem>().isNotNull(CatalogItem::getCatalogItemId));
    }

    // ── Test data helpers ────────────────────────────────────────────────────

    protected void insertCatalogItem(String id, String inventoryResourceId, String status) {
        CatalogItem item = new CatalogItem();
        item.setCatalogItemId(id);
        item.setInventoryResourceId(inventoryResourceId);
        item.setName("Test Item " + id);
        item.setStatus(status);
        item.setVersion(0L);
        catalogItemMapper.insert(item);
    }

    protected void insertInventoryResource(String id, int total, int reserved, String status) {
        InventoryResource inv = new InventoryResource();
        inv.setInventoryResourceId(id);
        inv.setResourceCode("RC-" + id);
        inv.setTotalQuantity(total);
        inv.setReservedQuantity(reserved);
        inv.setStatus(status);
        inv.setVersion(0L);
        inventoryResourceMapper.insert(inv);
    }

    protected void insertReservation(String id, String externalTradeNo, String catalogItemId,
                                      String inventoryResourceId, int quantity, String status,
                                      LocalDateTime expiresAt) {
        ReservationRecord r = new ReservationRecord();
        r.setReservationId(id);
        r.setExternalTradeNo(externalTradeNo);
        r.setCatalogItemId(catalogItemId);
        r.setInventoryResourceId(inventoryResourceId);
        r.setQuantity(quantity);
        r.setStatus(status);
        r.setExpiresAt(expiresAt);
        r.setVersion(0L);
        reservationRecordMapper.insert(r);
    }

    protected void insertOrder(String orderId, String externalTradeNo, String reservationId) {
        insertOrder(orderId, externalTradeNo, reservationId, "PENDING_PAYMENT", null);
    }

    protected void insertOrder(String orderId, String externalTradeNo, String reservationId,
                               String status, LocalDateTime confirmedAt) {
        TicketOrder order = new TicketOrder();
        order.setOrderId(orderId);
        order.setExternalTradeNo(externalTradeNo);
        order.setReservationId(reservationId);
        order.setStatus(status);
        order.setBuyerRef("test-buyer");
        order.setPaymentDeadlineAt(LocalDateTime.now().plusMinutes(30));
        order.setConfirmedAt(confirmedAt);
        order.setVersion(0L);
        ticketOrderMapper.insert(order);
    }

    protected void insertFulfillment(String fulfillmentId, String orderId, LocalDateTime confirmedAt) {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId(fulfillmentId);
        record.setOrderId(orderId);
        record.setStatus("PENDING");
        record.setPaymentProvider("MOCK_PROVIDER");
        record.setProviderEventId("mock-event-" + fulfillmentId);
        record.setConfirmedAt(confirmedAt);
        record.setVersion(0L);
        fulfillmentRecordMapper.insert(record);
    }

    // ── HTTP request helpers ─────────────────────────────────────────────────

    protected HttpEntity<Object> withIdempotencyKey(Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Object> withoutIdempotencyKey(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
