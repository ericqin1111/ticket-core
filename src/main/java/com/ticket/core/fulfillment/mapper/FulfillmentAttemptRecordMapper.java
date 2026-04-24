package com.ticket.core.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.fulfillment.entity.FulfillmentAttemptRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FulfillmentAttemptRecordMapper extends BaseMapper<FulfillmentAttemptRecord> {

    @Select("""
            SELECT attempt_id, fulfillment_id, attempt_no, trigger, execution_status, status, dispatcher_run_id,
                   executor_ref, execution_path, claimed_at, started_at, finished_at, delivery_result_json,
                   failure_json, failure_decision_json, provider_diagnostic_json, diagnostic_trace_json, created_at,
                   updated_at
            FROM fulfillment_attempt_record
            WHERE fulfillment_id = #{fulfillmentId}
            ORDER BY attempt_no ASC, claimed_at ASC, attempt_id ASC
            """)
    List<FulfillmentAttemptRecord> selectByFulfillmentId(@Param("fulfillmentId") String fulfillmentId);

    @Update("""
            UPDATE fulfillment_attempt_record
            SET status = 'SUCCEEDED',
                finished_at = #{finishedAt},
                delivery_result_json = #{deliveryResultJson},
                failure_json = NULL,
                diagnostic_trace_json = #{diagnosticTraceJson},
                updated_at = NOW(3)
            WHERE attempt_id = #{attemptId}
              AND fulfillment_id = #{fulfillmentId}
              AND status = 'EXECUTING'
            """)
    int markSucceeded(@Param("fulfillmentId") String fulfillmentId,
                      @Param("attemptId") String attemptId,
                      @Param("finishedAt") LocalDateTime finishedAt,
                      @Param("deliveryResultJson") String deliveryResultJson,
                      @Param("diagnosticTraceJson") String diagnosticTraceJson);

    @Update("""
            UPDATE fulfillment_attempt_record
            SET status = 'FAILED',
                finished_at = #{finishedAt},
                delivery_result_json = NULL,
                failure_json = #{failureJson},
                diagnostic_trace_json = #{diagnosticTraceJson},
                updated_at = NOW(3)
            WHERE attempt_id = #{attemptId}
              AND fulfillment_id = #{fulfillmentId}
              AND status = 'EXECUTING'
            """)
    int markFailed(@Param("fulfillmentId") String fulfillmentId,
                   @Param("attemptId") String attemptId,
                   @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("failureJson") String failureJson,
                   @Param("diagnosticTraceJson") String diagnosticTraceJson);

    @Update("""
            UPDATE fulfillment_attempt_record
            SET diagnostic_trace_json = #{diagnosticTraceJson},
                updated_at = NOW(3)
            WHERE attempt_id = #{attemptId}
              AND fulfillment_id = #{fulfillmentId}
              AND status = 'EXECUTING'
            """)
    int recordLeftProcessing(@Param("fulfillmentId") String fulfillmentId,
                             @Param("attemptId") String attemptId,
                             @Param("diagnosticTraceJson") String diagnosticTraceJson);
}
