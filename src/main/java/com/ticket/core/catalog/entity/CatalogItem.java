package com.ticket.core.catalog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("catalog_item")
public class CatalogItem {

    @TableId(type = IdType.INPUT)
    private String catalogItemId;
    private String inventoryResourceId;
    private String name;
    /** DRAFT, ACTIVE, OFF_SHELF */
    private String status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
