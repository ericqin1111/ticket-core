package com.ticket.core.catalog.controller;

import com.ticket.core.catalog.dto.SellableAvailabilityResponse;
import com.ticket.core.catalog.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog-items")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    /**
     * GET /catalog-items/{catalog_item_id}/sellable-availability
     * Returns a real-time sellable snapshot for pre-reservation validation.
     */
    @GetMapping("/{catalogItemId}/sellable-availability")
    public ResponseEntity<SellableAvailabilityResponse> getSellableAvailability(
            @PathVariable String catalogItemId) {
        return ResponseEntity.ok(catalogService.getSellableAvailability(catalogItemId));
    }
}
