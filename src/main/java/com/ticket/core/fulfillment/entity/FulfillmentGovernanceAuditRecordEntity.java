package com.ticket.core.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fulfillment_governance_audit_record")
public class FulfillmentGovernanceAuditRecordEntity {

    @TableId(type = IdType.INPUT)
    private String auditId;
    private String fulfillmentId;
    private String attemptId;
    private String actionType;
    private String fromStatus;
    private String toStatus;
    private String failureCategory;
    private String reasonCode;
    private String retryBudgetSnapshotJson;
    private String actorType;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
