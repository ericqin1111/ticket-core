package com.ticket.core.inventory.service;

import com.ticket.core.common.exception.BusinessException;
import com.ticket.core.common.exception.ErrorCode;
import com.ticket.core.inventory.entity.InventoryResource;
import com.ticket.core.inventory.mapper.InventoryResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryResourceMapper mapper;

    /**
     * Returns the inventory resource by ID or throws if not found / not ACTIVE.
     */
    public InventoryResource getActiveInventory(String inventoryResourceId) {
        InventoryResource resource = mapper.selectById(inventoryResourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            log.warn("Inventory resource not active: inventoryResourceId={}", inventoryResourceId);
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY,
                    "Inventory resource is unavailable: " + inventoryResourceId);
        }
        return resource;
    }

    /**
     * Atomically locks the requested quantity against the inventory resource.
     * Uses a conditional UPDATE with version guard to enforce strong consistency.
     *
     * @throws BusinessException INSUFFICIENT_INVENTORY if the lock cannot be acquired
     *                           (concurrent conflict or not enough sellable quantity)
     */
    public void lockQuantity(InventoryResource resource, int quantity) {
        int affected = mapper.lockInventory(resource.getInventoryResourceId(), quantity, resource.getVersion());
        if (affected == 0) {
            log.warn("Inventory lock failed (insufficient or concurrent conflict): inventoryResourceId={}, requested={}",
                    resource.getInventoryResourceId(), quantity);
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY);
        }
        log.info("Inventory locked: inventoryResourceId={}, quantity={}", resource.getInventoryResourceId(), quantity);
    }

    public void restoreQuantity(InventoryResource resource, int quantity) {
        int affected = mapper.restoreInventory(resource.getInventoryResourceId(), quantity, resource.getVersion());
        if (affected == 0) {
            log.warn("Inventory restore failed (concurrent conflict or inconsistent reserved quantity): inventoryResourceId={}, quantity={}",
                    resource.getInventoryResourceId(), quantity);
            throw new IllegalStateException("Inventory restore failed for resource: " + resource.getInventoryResourceId());
        }
        log.info("Inventory restored: inventoryResourceId={}, quantity={}", resource.getInventoryResourceId(), quantity);
    }
}
