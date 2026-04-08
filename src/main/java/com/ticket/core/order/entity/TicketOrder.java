package com.ticket.core.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_order")
public class TicketOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;
    private String externalTradeNo;
    private String reservationId;
    /** PENDING_PAYMENT, CONFIRMED, CLOSED */
    private String status;
    private String buyerRef;
    private String contactPhone;
    private String contactEmail;
    private String submissionContextJson;
    private LocalDateTime paymentDeadlineAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime closedAt;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
