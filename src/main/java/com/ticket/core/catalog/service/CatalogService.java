package com.ticket.core.catalog.service;

import com.ticket.core.catalog.dto.SellableAvailabilityResponse;
import com.ticket.core.catalog.entity.CatalogItem;
import com.ticket.core.catalog.mapper.CatalogItemMapper;
import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.inventory.mapper.InventoryResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogItemMapper catalogItemMapper;
    private final InventoryResourceMapper inventoryResourceMapper;

    /**
     * Returns a snapshot of sellable availability for the given catalog item.
     * Delegates to Inventory module for raw quantity data.
     *
     * @throws BusinessException CATALOG_ITEM_NOT_SELLABLE if item is not ACTIVE
     */
    public SellableAvailabilityResponse getSellableAvailability(String catalogItemId) {
        CatalogItem item = catalogItemMapper.selectById(catalogItemId);
        if (item == null || !"ACTIVE".equals(item.getStatus())) {
            log.warn("Catalog item not sellable: catalogItemId={}", catalogItemId);
            throw new BusinessException(ErrorCode.CATALOG_ITEM_NOT_SELLABLE);
        }

        InventoryResource inventory = inventoryResourceMapper.selectById(item.getInventoryResourceId());
        if (inventory == null) {
            log.error("Orphaned catalog item — inventory resource missing: catalogItemId={}, inventoryResourceId={}",
                    catalogItemId, item.getInventoryResourceId());
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                    "Inventory resource not found for catalog item: " + catalogItemId);
        }

        int sellable = inventory.getTotalQuantity() - inventory.getReservedQuantity();
        String availabilityStatus = resolveAvailabilityStatus(item, inventory, sellable);

        return SellableAvailabilityResponse.builder()
                .catalogItemId(item.getCatalogItemId())
                .inventoryResourceId(inventory.getInventoryResourceId())
                .sellableQuantity(Math.max(0, sellable))
                .reservedQuantity(inventory.getReservedQuantity())
                .status(availabilityStatus)
                .checkedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    /**
     * Validates that a catalog item exists and is ACTIVE, and returns the bound inventory resource ID.
     */
    public CatalogItem validateAndGetActiveItem(String catalogItemId) {
        CatalogItem item = catalogItemMapper.selectById(catalogItemId);
        if (item == null || !"ACTIVE".equals(item.getStatus())) {
            throw new BusinessException(ErrorCode.CATALOG_ITEM_NOT_SELLABLE);
        }
        return item;
    }

    private String resolveAvailabilityStatus(CatalogItem item, InventoryResource inventory, int sellable) {
        if (!"ACTIVE".equals(inventory.getStatus())) {
            return "OFF_SHELF";
        }
        if (sellable <= 0) {
            return "SOLD_OUT";
        }
        // LOW_STOCK threshold: less than 10% of total or fewer than 5 units
        int total = inventory.getTotalQuantity();
        if (sellable < 5 || (total > 0 && (double) sellable / total < 0.10)) {
            return "LOW_STOCK";
        }
        return "SELLABLE";
    }
}
