package com.ticket.core.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_trail_event")
public class AuditTrailEvent {

    @TableId(type = IdType.INPUT)
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String externalTradeNo;
    private String orderId;
    private String reservationId;
    private String inventoryResourceId;
    private String fulfillmentId;
    private String providerEventId;
    private String actorType;
    private String actorRef;
    private String requestId;
    private String idempotencyKey;
    private String reasonCode;
    private String payloadSummaryJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
