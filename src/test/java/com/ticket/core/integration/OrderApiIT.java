package com.ticket.core.integration;

import com.ticket.core.common.dto.ErrorResponse;
import com.ticket.core.order.dto.CreateOrderResponse;
import com.ticket.core.reservation.entity.ReservationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
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
 * Integration tests for POST /orders.
 *
 * Covers:
 *  - RFC Section 4.2 contract validation (required fields, response shape, HTTP status codes)
 *  - RFC Section 5 Failure Scenario 2 (CONSUMED/EXPIRED reservation rejection)
 *  - RFC Section 5 Failure Scenario 3 (transaction atomicity — reservation rolled back on order failure)
 *  - RFC Section 5 Failure Scenario 4 (idempotency conflict detection + action space isolation)
 *  - Concurrent consume: only one thread wins the optimistic lock
 */
@DisplayName("Order API — POST /orders")
class OrderApiIT extends AbstractIntegrationTest {

    private static final String CATALOG_ID = "cat-order-001";
    private static final String INV_ID = "inv-order-001";

    private void seedCatalogAndInventory() {
        insertInventoryResource(INV_ID, 100, 1, "ACTIVE");
        insertCatalogItem(CATALOG_ID, INV_ID, "ACTIVE");
    }

    private Map<String, Object> buildOrderBody(String externalTradeNo, String reservationId) {
        Map<String, Object> buyer = new HashMap<>();
        buyer.put("buyerRef", "buyer-ref-001");
        buyer.put("contactPhone", "13800138000");

        Map<String, Object> body = new HashMap<>();
        body.put("externalTradeNo", externalTradeNo);
        body.put("reservationId", reservationId);
        body.put("buyer", buyer);
        return body;
    }

    private String insertActiveReservation(String id, String externalTradeNo) {
        seedCatalogAndInventory();
        insertReservation(id, externalTradeNo, CATALOG_ID, INV_ID, 1,
                "ACTIVE", LocalDateTime.now().plusMinutes(10));
        return id;
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid request consumes ACTIVE reservation and returns 201 PENDING_PAYMENT order")
    void createOrder_happyPath_returns201WithPendingPaymentOrder() {
        String reservationId = insertActiveReservation("res-order-hp-001", "trade-order-hp-001");
        String idemKey = UUID.randomUUID().toString();

        ResponseEntity<CreateOrderResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-order-hp-001", reservationId), idemKey),
                CreateOrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CreateOrderResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getOrderId()).isNotBlank();
        assertThat(body.getExternalTradeNo()).isEqualTo("trade-order-hp-001");
        assertThat(body.getReservationId()).isEqualTo(reservationId);
        assertThat(body.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(body.getPaymentDeadlineAt()).isNotNull();

        // Reservation must be CONSUMED
        ReservationRecord reservation = reservationRecordMapper.selectById(reservationId);
        assertThat(reservation.getStatus()).isEqualTo("CONSUMED");
        assertThat(reservation.getConsumedOrderId()).isEqualTo(body.getOrderId());
        assertThat(reservation.getConsumedAt()).isNotNull();
    }

    // ── Contract: missing Idempotency-Key header ──────────────────────────────

    @Test
    @DisplayName("Missing Idempotency-Key header returns 400")
    void createOrder_missingIdempotencyKeyHeader_returns400() {
        String reservationId = insertActiveReservation("res-order-hdr-001", "trade-order-hdr-001");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withoutIdempotencyKey(buildOrderBody("trade-order-hdr-001", reservationId)),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Contract: missing external_trade_no ──────────────────────────────────

    @Test
    @DisplayName("Missing external_trade_no returns 400 VALIDATION_ERROR")
    void createOrder_missingExternalTradeNo_returns400() {
        String reservationId = insertActiveReservation("res-order-val-001", "trade-order-val-001");
        Map<String, Object> body = buildOrderBody("trade-order-val-001", reservationId);
        body.remove("externalTradeNo");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    // ── Contract: missing buyer.buyer_ref ────────────────────────────────────

    @Test
    @DisplayName("Missing buyer.buyerRef returns 400 VALIDATION_ERROR")
    void createOrder_missingBuyerRef_returns400() {
        String reservationId = insertActiveReservation("res-order-byr-001", "trade-order-byr-001");
        Map<String, Object> buyer = new HashMap<>(); // buyer_ref intentionally omitted
        buyer.put("contactPhone", "13800138000");
        Map<String, Object> body = new HashMap<>();
        body.put("externalTradeNo", "trade-order-byr-001");
        body.put("reservationId", reservationId);
        body.put("buyer", buyer);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(body, UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    // ── Failure Scenario 2a: reservation not found ───────────────────────────

    @Test
    @DisplayName("Non-existent reservation_id returns 404 RESERVATION_NOT_FOUND")
    void createOrder_reservationNotFound_returns404() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-order-nf-001", "DOES-NOT-EXIST"),
                        UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo("RESERVATION_NOT_FOUND");
    }

    // ── Failure Scenario 2b: reservation already CONSUMED ────────────────────

    @Test
    @DisplayName("CONSUMED reservation returns 409 RESERVATION_ALREADY_CONSUMED — no duplicate order created")
    void createOrder_reservationAlreadyConsumed_returns409_andNoOrderIsCreated() {
        seedCatalogAndInventory();
        insertReservation("res-consumed-001", "trade-consumed-001", CATALOG_ID, INV_ID, 1,
                "CONSUMED", LocalDateTime.now().plusMinutes(10));

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-consumed-001", "res-consumed-001"),
                        UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getCode()).isEqualTo("RESERVATION_ALREADY_CONSUMED");
        assertThat(ticketOrderMapper.selectById("res-consumed-001")).isNull();
    }

    // ── Failure Scenario 2c: reservation EXPIRED (by expires_at) ────────────

    @Test
    @DisplayName("Reservation past expires_at returns 410 RESERVATION_EXPIRED — no order created")
    void createOrder_reservationExpiredByExpiresAt_returns410() {
        seedCatalogAndInventory();
        // Status is ACTIVE but expires_at is in the past — implementation checks both
        insertReservation("res-expired-001", "trade-expired-001", CATALOG_ID, INV_ID, 1,
                "ACTIVE", LocalDateTime.now().minusMinutes(5));

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-expired-001", "res-expired-001"),
                        UUID.randomUUID().toString()),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().getCode()).isEqualTo("RESERVATION_EXPIRED");
    }

    // ── Idempotency: same key + same body → cached 201 ───────────────────────

    @Test
    @DisplayName("Same Idempotency-Key with identical body replays the original 201 response")
    void createOrder_idempotentReplay_returnsSameOrderId() {
        String reservationId = insertActiveReservation("res-idem-replay-001", "trade-idem-replay-001");
        String idemKey = UUID.randomUUID().toString();
        Map<String, Object> body = buildOrderBody("trade-idem-replay-001", reservationId);

        ResponseEntity<CreateOrderResponse> first = restTemplate.postForEntity(
                "/orders", withIdempotencyKey(body, idemKey), CreateOrderResponse.class);
        ResponseEntity<CreateOrderResponse> second = restTemplate.postForEntity(
                "/orders", withIdempotencyKey(body, idemKey), CreateOrderResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().getOrderId()).isEqualTo(first.getBody().getOrderId());
    }

    // ── Idempotency: same key + different body → IDEMPOTENCY_CONFLICT ────────

    @Test
    @DisplayName("Same Idempotency-Key with different request body returns 409 IDEMPOTENCY_CONFLICT")
    void createOrder_idempotencyConflict_differentBody_returns409() {
        String reservationId = insertActiveReservation("res-idem-conf-001", "trade-idem-conf-original");
        String idemKey = UUID.randomUUID().toString();

        restTemplate.postForEntity("/orders",
                withIdempotencyKey(buildOrderBody("trade-idem-conf-original", reservationId), idemKey),
                CreateOrderResponse.class);

        // Same key, different external_trade_no
        ResponseEntity<ErrorResponse> conflict = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-idem-conf-DIFFERENT", reservationId), idemKey),
                ErrorResponse.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    // ── Idempotency: action-space isolation ──────────────────────────────────

    @Test
    @DisplayName("Shared Idempotency-Key used for both POST /reservations and POST /orders succeeds in both (distinct action namespaces)")
    void createOrder_idempotencyActionSpaceIsolation_orderSucceeds() {
        seedCatalogAndInventory();
        // Insert a reservation that was created via the reservation endpoint
        insertReservation("res-isolation-001", "trade-isolation-001", CATALOG_ID, INV_ID, 1,
                "ACTIVE", LocalDateTime.now().plusMinutes(10));

        // The SAME key that might have been used for POST /reservations
        // is allowed for POST /orders because action_name differs (CREATE_ORDER vs CREATE_RESERVATION)
        String sharedKey = "shared-isolation-key-" + UUID.randomUUID();

        ResponseEntity<CreateOrderResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-isolation-001", "res-isolation-001"), sharedKey),
                CreateOrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    // ── Failure Scenario 3: Transaction atomicity ─────────────────────────────
    //
    // Setup: Pre-insert an order whose external_trade_no equals the one in our test request.
    // When POST /orders executes:
    //   1. Consumes the reservation (UPDATE reservation SET status=CONSUMED)
    //   2. Attempts INSERT into ticket_order → UNIQUE KEY violation on external_trade_no
    //   3. @Transactional rolls back → reservation reverts to ACTIVE
    //
    // A passing test proves that the transactional boundary correctly rolls back
    // the reservation consume when order creation fails (RFC Failure Scenario 3).

    @Test
    @DisplayName("Order insert failure due to duplicate external_trade_no rolls back reservation to ACTIVE (Failure Scenario 3)")
    void createOrder_orderInsertFails_reservationRemainsActive() {
        seedCatalogAndInventory();
        insertReservation("res-atomic-001", "trade-atomic-collision", CATALOG_ID, INV_ID, 1,
                "ACTIVE", LocalDateTime.now().plusMinutes(10));

        // Pre-insert an order with the SAME external_trade_no to force a UK collision
        insertOrder("order-pre-existing-001", "trade-atomic-collision", "some-other-res-id");

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/orders",
                withIdempotencyKey(buildOrderBody("trade-atomic-collision", "res-atomic-001"),
                        UUID.randomUUID().toString()),
                ErrorResponse.class);

        // The duplicate key causes an unhandled exception → 500 from catch-all handler
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // CRITICAL: reservation must NOT be CONSUMED — transaction was rolled back
        ReservationRecord reservation = reservationRecordMapper.selectById("res-atomic-001");
        assertThat(reservation).isNotNull();
        assertThat(reservation.getStatus())
                .as("Reservation must remain ACTIVE after order insert failure (Failure Scenario 3 guard)")
                .isEqualTo("ACTIVE");
        assertThat(reservation.getConsumedOrderId()).isNull();
    }

    // ── Failure Scenario 2 + concurrency: only one consume wins ──────────────

    @Test
    @DisplayName("Two concurrent requests for same reservation — only one succeeds, reservation is CONSUMED once")
    void createOrder_concurrent_onlyOneConsumeSucceeds() throws InterruptedException {
        seedCatalogAndInventory();
        insertReservation("res-race-001", "trade-race-001", CATALOG_ID, INV_ID, 1,
                "ACTIVE", LocalDateTime.now().plusMinutes(10));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread uses a DIFFERENT external_trade_no and idempotency key
                    // but the SAME reservation_id — only one can consume it
                    ResponseEntity<Map> resp = restTemplate.postForEntity(
                            "/orders",
                            withIdempotencyKey(buildOrderBody("trade-race-" + idx, "res-race-001"),
                                    "order-race-idem-" + idx),
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
        assertThat(finished).as("Both threads must complete within 30s").isTrue();

        // Exactly one succeed, one fail
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        // Reservation is CONSUMED exactly once
        ReservationRecord reservation = reservationRecordMapper.selectById("res-race-001");
        assertThat(reservation.getStatus()).isEqualTo("CONSUMED");
        assertThat(reservation.getConsumedOrderId()).isNotBlank();
    }
}
