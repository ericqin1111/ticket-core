package com.ticket.core.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fulfillment_record")
public class FulfillmentRecord {

    @TableId(type = IdType.INPUT)
    private String fulfillmentId;
    private String orderId;
    /** PENDING */
    private String status;
    private String paymentProvider;
    private String providerEventId;
    private String providerPaymentId;
    private LocalDateTime confirmedAt;
    private String channelContextJson;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
