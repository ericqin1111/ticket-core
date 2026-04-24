package com.ticket.core.fulfillment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fulfillment_attempt_record")
public class FulfillmentAttemptRecord {

    @TableId(type = IdType.INPUT)
    private String attemptId;
    private String fulfillmentId;
    private Integer attemptNo;
    private String trigger;
    private String executionStatus;
    private String status;
    private String dispatcherRunId;
    private String executorRef;
    private String executionPath;
    private LocalDateTime claimedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String deliveryResultJson;
    private String failureJson;
    private String failureDecisionJson;
    private String providerDiagnosticJson;
    private String diagnosticTraceJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
