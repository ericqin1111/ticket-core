package com.ticket.core.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reservation_record")
public class ReservationRecord {

    @TableId(type = IdType.INPUT)
    private String reservationId;
    private String externalTradeNo;
    private String catalogItemId;
    private String inventoryResourceId;
    private Integer quantity;
    /** ACTIVE, CONSUMED, EXPIRED */
    private String status;
    private LocalDateTime expiresAt;
    private String consumedOrderId;
    private LocalDateTime consumedAt;
    private String channelContextJson;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
