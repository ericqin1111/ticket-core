package com.ticket.core.integration;

import com.ticket.core.catalog.dto.SellableAvailabilityResponse;
import com.ticket.core.common.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for GET /catalog-items/{catalogItemId}/sellable-availability.
 *
 * Validates RFC Section 4.2 JSON contract shape, status enum values, and
 * CATALOG_ITEM_NOT_SELLABLE error path (RFC Section 5 implied by CatalogService).
 */
@DisplayName("Catalog API — sellable availability contract")
class CatalogApiIT extends AbstractIntegrationTest {

    // ── Happy path: SELLABLE ─────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE item with sufficient stock returns 200 SELLABLE")
    void getSellableAvailability_activeItem_sufficientStock_returnsSellable() {
        insertInventoryResource("inv-cat-001", 100, 10, "ACTIVE");
        insertCatalogItem("cat-001", "inv-cat-001", "ACTIVE");

        ResponseEntity<SellableAvailabilityResponse> response =
                restTemplate.getForEntity("/catalog-items/cat-001/sellable-availability",
                        SellableAvailabilityResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SellableAvailabilityResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCatalogItemId()).isEqualTo("cat-001");
        assertThat(body.getInventoryResourceId()).isEqualTo("inv-cat-001");
        assertThat(body.getSellableQuantity()).isEqualTo(90);
        assertThat(body.getReservedQuantity()).isEqualTo(10);
        assertThat(body.getStatus()).isEqualTo("SELLABLE");
        assertThat(body.getCheckedAt()).isNotNull();
    }

    // ── Happy path: LOW_STOCK ────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE item with < 5 sellable units returns 200 LOW_STOCK")
    void getSellableAvailability_activeItem_fewUnitsLeft_returnsLowStock() {
        insertInventoryResource("inv-cat-002", 100, 97, "ACTIVE");
        insertCatalogItem("cat-002", "inv-cat-002", "ACTIVE");

        ResponseEntity<SellableAvailabilityResponse> response =
                restTemplate.getForEntity("/catalog-items/cat-002/sellable-availability",
                        SellableAvailabilityResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("LOW_STOCK");
        assertThat(response.getBody().getSellableQuantity()).isEqualTo(3);
    }

    // ── Happy path: SOLD_OUT ─────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE item with zero sellable units returns 200 SOLD_OUT")
    void getSellableAvailability_activeItem_noInventoryLeft_returnsSoldOut() {
        insertInventoryResource("inv-cat-003", 10, 10, "ACTIVE");
        insertCatalogItem("cat-003", "inv-cat-003", "ACTIVE");

        ResponseEntity<SellableAvailabilityResponse> response =
                restTemplate.getForEntity("/catalog-items/cat-003/sellable-availability",
                        SellableAvailabilityResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("SOLD_OUT");
        assertThat(response.getBody().getSellableQuantity()).isEqualTo(0);
    }

    // ── Happy path: OFF_SHELF (inventory not ACTIVE) ─────────────────────────

    @Test
    @DisplayName("ACTIVE catalog item with FROZEN inventory returns 200 OFF_SHELF")
    void getSellableAvailability_activeItem_frozenInventory_returnsOffShelf() {
        insertInventoryResource("inv-cat-004", 50, 0, "FROZEN");
        insertCatalogItem("cat-004", "inv-cat-004", "ACTIVE");

        ResponseEntity<SellableAvailabilityResponse> response =
                restTemplate.getForEntity("/catalog-items/cat-004/sellable-availability",
                        SellableAvailabilityResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("OFF_SHELF");
    }

    // ── Error: catalog item not ACTIVE ───────────────────────────────────────

    @Test
    @DisplayName("OFF_SHELF catalog item returns 422 CATALOG_ITEM_NOT_SELLABLE")
    void getSellableAvailability_offShelfCatalogItem_returns422() {
        insertInventoryResource("inv-cat-005", 50, 0, "ACTIVE");
        insertCatalogItem("cat-005", "inv-cat-005", "OFF_SHELF");

        ResponseEntity<ErrorResponse> response =
                restTemplate.getForEntity("/catalog-items/cat-005/sellable-availability",
                        ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CATALOG_ITEM_NOT_SELLABLE");
        assertThat(response.getBody().getRetryable()).isFalse();
        assertThat(response.getBody().getRequestId()).isNotBlank();
    }

    // ── Error: catalog item does not exist ───────────────────────────────────

    @Test
    @DisplayName("Non-existent catalog item returns 422 CATALOG_ITEM_NOT_SELLABLE")
    void getSellableAvailability_nonExistentCatalogItemId_returns422() {
        ResponseEntity<ErrorResponse> response =
                restTemplate.getForEntity("/catalog-items/DOES-NOT-EXIST/sellable-availability",
                        ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CATALOG_ITEM_NOT_SELLABLE");
    }
}
