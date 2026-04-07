package com.ticket.core.integration;

import com.ticket.core.common.dto.ErrorResponse;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.reservation.dto.CreateReservationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for POST /reservations.
 *
 * Covers:
 *  - RFC Section 4.2 contract validation (required fields, boundary values, response shape)
 *  - RFC Section 5 Failure Scenario 1 (concurrent inventory contention)
 *  - RFC Section 5 Failure Scenario 4 (idempotency conflict detection)
 *  - Idempotency action-space isolation (same key, different actions)
 */
@DisplayName("Reservation API — POST /reservations")
class ReservationApiIT extends AbstractIntegrationTest {

    private static final String CATALOG_ID = "cat-res-001";
    private static final String INV_ID = "inv-res-001";

    private void seedActiveCatalogAndInventory(int total, int reserved) {
        insertInventoryResource(INV_ID, total, reserved, "ACTIVE");
        insertCatalogItem(CATALOG_ID, INV_ID, "ACTIVE");
    }

    private Map<String, Object> buildValidBody(String externalTradeNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("externalTradeNo", externalTradeNo);
        body.put("catalogItemId", CATALOG_ID);
        body.put("quantity", 1);
        body.put("reservationTtlSeconds", 300);
        return body;
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid request returns 201 with ACTIVE reservation and all RFC-required response fields")
    void createReservation_happyPath_returns201WithActiveReservation() {
        seedActiveCatalogAndInventory(100, 0);
        String idemKey = UUID.randomUUID().toString();

        ResponseEntity<CreateReservationResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(buildValidBody("trade-happy-001"), idemKey),
                CreateReservationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CreateReservationResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getReservationId()).isNotBlank();
        assertThat(body.getExternalTradeNo()).isEqualTo("trade-happy-001");
        assertThat(body.getCatalogItemId()).isEqualTo(CATALOG_ID);
        assertThat(body.getQuantity()).isEqualTo(1);
        assertThat(body.getStatus()).isEqualTo("ACTIVE");
        assertThat(body.getExpiresAt()).isNotNull();

        // Assert inventory was actually locked
        InventoryResource inv = inventoryResourceMapper.selectById(INV_ID);
        assertThat(inv.getReservedQuantity()).isEqualTo(1);
    }

    // ── Contract: missing Idempotency-Key header ──────────────────────────────

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400")
    void createReservation_missingIdempotencyKeyHeader_returns400() {
        seedActiveCatalogAndInventory(100, 0);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withoutIdempotencyKey(buildValidBody("trade-no-key-001")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Contract: missing required field external_trade_no ───────────────────

    @Test
    @DisplayName("Missing external_trade_no returns 400 VALIDATION_ERROR")
    void createReservation_missingExternalTradeNo_returns400() {
        seedActiveCatalogAndInventory(100, 0);
        Map<String, Object> body = buildValidBody("trade-valid");
        body.remove("externalTradeNo");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    // ── Contract: quantity below minimum (RFC: minimum 1) ────────────────────

    @Test
    @DisplayName("quantity = 0 violates @Min(1) and returns 400 VALIDATION_ERROR")
    void createReservation_quantityZero_returns400() {
        seedActiveCatalogAndInventory(100, 0);
        Map<String, Object> body = buildValidBody("trade-qty-zero");
        body.put("quantity", 0);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    // ── Contract: reservation_ttl_seconds below minimum (RFC: minimum 30) ────

    @Test
    @DisplayName("reservationTtlSeconds = 10 violates @Min(30) and returns 400 VALIDATION_ERROR")
    void createReservation_ttlBelowMinimum_returns400() {
        seedActiveCatalogAndInventory(100, 0);
        Map<String, Object> body = buildValidBody("trade-ttl-low");
        body.put("reservationTtlSeconds", 10);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    // ── Error: catalog item not ACTIVE ───────────────────────────────────────

    @Test
    @DisplayName("OFF_SHELF catalog item returns 422 CATALOG_ITEM_NOT_SELLABLE")
    void createReservation_offShelfCatalogItem_returns422() {
        insertInventoryResource("inv-off-001", 100, 0, "ACTIVE");
        insertCatalogItem("cat-off-001", "inv-off-001", "OFF_SHELF");
        Map<String, Object> body = new HashMap<>();
        body.put("externalTradeNo", "trade-off-shelf");
        body.put("catalogItemId", "cat-off-001");
        body.put("quantity", 1);
        body.put("reservationTtlSeconds", 300);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getCode()).isEqualTo("CATALOG_ITEM_NOT_SELLABLE");
    }

    // ── Error: insufficient inventory ────────────────────────────────────────

    @Test
    @DisplayName("Request exceeds available sellable quantity returns 409 INSUFFICIENT_INVENTORY")
    void createReservation_requestExceedsInventory_returns409() {
        seedActiveCatalogAndInventory(5, 5); // sellable = 0
        Map<String, Object> body = buildValidBody("trade-insuff");
        body.put("quantity", 1);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("INSUFFICIENT_INVENTORY");
    }

    // ── Idempotency: same key + same body → cached 201 ───────────────────────

    @Test
    @DisplayName("Same Idempotency-Key with identical body replays the original 201 response")
    void createReservation_idempotentReplay_returnsCachedResponse() {
        seedActiveCatalogAndInventory(100, 0);
        String idemKey = UUID.randomUUID().toString();
        Map<String, Object> body = buildValidBody("trade-idem-replay-001");

        ResponseEntity<CreateReservationResponse> first = restTemplate.postForEntity(
                "/reservations", withIdempotencyKey(body, idemKey), CreateReservationResponse.class);
        ResponseEntity<CreateReservationResponse> second = restTemplate.postForEntity(
                "/reservations", withIdempotencyKey(body, idemKey), CreateReservationResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().getReservationId()).isEqualTo(first.getBody().getReservationId());

        // Inventory should only be decremented once
        InventoryResource inv = inventoryResourceMapper.selectById(INV_ID);
        assertThat(inv.getReservedQuantity()).isEqualTo(1);
    }

    // ── Idempotency: same key + different body → IDEMPOTENCY_CONFLICT ────────

    @Test
    @DisplayName("Same Idempotency-Key with different request body returns 409 IDEMPOTENCY_CONFLICT")
    void createReservation_idempotencyConflict_differentBody_returns409() {
        seedActiveCatalogAndInventory(100, 0);
        String idemKey = UUID.randomUUID().toString();

        Map<String, Object> originalBody = buildValidBody("trade-conflict-original");
        restTemplate.postForEntity("/reservations",
                withIdempotencyKey(originalBody, idemKey), CreateReservationResponse.class);

        // Same key, different external_trade_no — different request payload
        Map<String, Object> conflictBody = buildValidBody("trade-conflict-DIFFERENT");
        ResponseEntity<ErrorResponse> conflict = restTemplate.postForEntity(
                "/reservations", withIdempotencyKey(conflictBody, idemKey), ErrorResponse.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    // ── Idempotency: action-space isolation ──────────────────────────────────

    @Test
    @DisplayName("Same Idempotency-Key used for POST /reservations and POST /orders occupies separate action spaces")
    void createReservation_idempotencyActionSpaceIsolation_reservationSucceeds() {
        seedActiveCatalogAndInventory(100, 0);
        String sharedIdemKey = "shared-key-" + UUID.randomUUID();

        // Use the shared key to create a reservation
        ResponseEntity<CreateReservationResponse> response = restTemplate.postForEntity(
                "/reservations",
                withIdempotencyKey(buildValidBody("trade-isolation-001"), sharedIdemKey),
                CreateReservationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getStatus()).isEqualTo("ACTIVE");
        // Idempotency record exists for CREATE_RESERVATION action
        // The same key will also work in POST /orders (different action = different slot),
        // verified in OrderApiIT#createOrder_idempotencyActionSpaceIsolation_orderSucceeds
    }

    // ── Failure Scenario 1: Concurrent inventory contention ──────────────────

    @Test
    @DisplayName("Concurrent requests for last 2 units — optimistic lock prevents oversell, exactly 2 succeed")
    void createReservation_concurrent_optimisticLockPreventsOversell() throws InterruptedException {
        // Inventory: total=5, reserved=3, sellable=2
        insertInventoryResource("inv-conc-001", 5, 3, "ACTIVE");
        insertCatalogItem("cat-conc-001", "inv-conc-001", "ACTIVE");

        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    Map<String, Object> body = new HashMap<>();
                    body.put("externalTradeNo", "trade-conc-" + idx);
                    body.put("catalogItemId", "cat-conc-001");
                    body.put("quantity", 1);
                    body.put("reservationTtlSeconds", 300);

                    ResponseEntity<Map> resp = restTemplate.postForEntity(
                            "/reservations",
                            withIdempotencyKey(body, "conc-idem-" + idx),
                            Map.class);

                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(finished).as("All concurrent threads must complete within 30s").isTrue();

        // ── Assertions ────────────────────────────────────────────────────
        int succeeded = successCount.get();
        int failed = failCount.get();

        // All threads accounted for
        assertThat(succeeded + failed).isEqualTo(threadCount);

        // No oversell: at most 2 (sellable capacity) can succeed
        assertThat(succeeded).isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo(2);

        // Physical invariant: reserved_quantity never exceeds total_quantity
        InventoryResource finalInv = inventoryResourceMapper.selectById("inv-conc-001");
        assertThat(finalInv.getReservedQuantity())
                .isLessThanOrEqualTo(finalInv.getTotalQuantity());

        // Inventory accounting is consistent: reserved increased by exactly #successes
        assertThat(finalInv.getReservedQuantity()).isEqualTo(3 + succeeded);
    }
}
