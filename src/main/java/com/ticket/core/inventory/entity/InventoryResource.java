package com.ticket.core.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inventory_resource")
public class InventoryResource {

    @TableId(type = IdType.INPUT)
    private String inventoryResourceId;
    private String resourceCode;
    private Integer totalQuantity;
    private Integer reservedQuantity;
    /** ACTIVE, FROZEN, OFFLINE */
    private String status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
