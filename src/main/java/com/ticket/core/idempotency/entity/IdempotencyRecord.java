package com.ticket.core.idempotency.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.INPUT)
    private String idempotencyRecordId;
    private String actionName;
    private String idempotencyKey;
    private String requestHash;
    private String externalTradeNo;
    private String resourceType;
    private String resourceId;
    /** PROCESSING, SUCCEEDED, FAILED */
    private String status;
    private String responsePayload;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
