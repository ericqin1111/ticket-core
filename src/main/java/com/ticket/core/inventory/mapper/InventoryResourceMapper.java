package com.ticket.core.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.inventory.entity.InventoryResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryResourceMapper extends BaseMapper<InventoryResource> {

    /**
     * Atomically increments reserved_quantity by the requested amount, guarded by:
     *  - version check (optimistic lock)
     *  - sellable quantity check (total - reserved >= quantity)
     *  - resource status must be ACTIVE
     *
     * Returns 1 on success, 0 if the condition is not met (concurrent conflict or insufficient inventory).
     */
    @Update("UPDATE inventory_resource " +
            "SET reserved_quantity = reserved_quantity + #{quantity}, " +
            "    version = version + 1, " +
            "    updated_at = NOW(3) " +
            "WHERE inventory_resource_id = #{inventoryResourceId} " +
            "  AND status = 'ACTIVE' " +
            "  AND version = #{version} " +
            "  AND (total_quantity - reserved_quantity) >= #{quantity}")
    int lockInventory(@Param("inventoryResourceId") String inventoryResourceId,
                      @Param("quantity") int quantity,
                      @Param("version") long version);
}
