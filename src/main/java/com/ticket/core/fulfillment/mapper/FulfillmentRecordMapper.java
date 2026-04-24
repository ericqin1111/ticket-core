package com.ticket.core.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FulfillmentRecordMapper extends BaseMapper<FulfillmentRecord> {

    @Select("""
            <script>
            SELECT fulfillment_id, order_id, status, payment_provider, provider_event_id, provider_payment_id,
                   confirmed_at, channel_context_json, current_attempt_id, latest_attempt_id, processing_started_at,
                   processing_timeout_at, terminal_at, execution_path, delivery_result_json, last_failure_json,
                   last_diagnostic_trace_json, retry_policy_json, retry_state_json, latest_failure_decision_json,
                   version, created_at, updated_at
            FROM fulfillment_record
            WHERE status = 'PENDING'
            <if test="cursorCreatedAt != null and cursorFulfillmentId != null">
              AND (
                    created_at &gt; #{cursorCreatedAt}
                    OR (created_at = #{cursorCreatedAt} AND fulfillment_id &gt; #{cursorFulfillmentId})
                  )
            </if>
            ORDER BY created_at ASC, fulfillment_id ASC
            LIMIT #{limit}
            </script>
            """)
    List<FulfillmentRecord> selectDispatchableBatch(@Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                   @Param("cursorFulfillmentId") String cursorFulfillmentId,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT fulfillment_id, order_id, status, payment_provider, provider_event_id, provider_payment_id,
                   confirmed_at, channel_context_json, current_attempt_id, latest_attempt_id, processing_started_at,
                   processing_timeout_at, terminal_at, execution_path, delivery_result_json, last_failure_json,
                   last_diagnostic_trace_json, retry_policy_json, retry_state_json, latest_failure_decision_json,
                   version, created_at, updated_at
            FROM fulfillment_record
            WHERE status = 'PROCESSING'
              AND processing_started_at &lt;= #{processingStartedBefore}
            ORDER BY processing_started_at ASC, fulfillment_id ASC
            LIMIT #{limit}
            """)
    List<FulfillmentRecord> selectStuckProcessingBatch(@Param("processingStartedBefore") LocalDateTime processingStartedBefore,
                                                       @Param("limit") int limit);

    @Update("""
            UPDATE fulfillment_record
            SET status = 'PROCESSING',
                current_attempt_id = #{attemptId},
                processing_started_at = #{processingStartedAt},
                execution_path = #{executionPath},
                last_diagnostic_trace_json = #{diagnosticTraceJson},
                version = version + 1,
                updated_at = NOW(3)
            WHERE fulfillment_id = #{fulfillmentId}
              AND status = 'PENDING'
              AND version = #{expectedVersion}
            """)
    int claimForProcessing(@Param("fulfillmentId") String fulfillmentId,
                           @Param("attemptId") String attemptId,
                           @Param("processingStartedAt") LocalDateTime processingStartedAt,
                           @Param("executionPath") String executionPath,
                           @Param("diagnosticTraceJson") String diagnosticTraceJson,
                           @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE fulfillment_record
            SET status = 'SUCCEEDED',
                terminal_at = #{terminalAt},
                delivery_result_json = #{deliveryResultJson},
                last_failure_json = NULL,
                last_diagnostic_trace_json = #{diagnosticTraceJson},
                version = version + 1,
                updated_at = NOW(3)
            WHERE fulfillment_id = #{fulfillmentId}
              AND status = 'PROCESSING'
              AND current_attempt_id = #{attemptId}
              AND version = #{expectedVersion}
            """)
    int markSucceeded(@Param("fulfillmentId") String fulfillmentId,
                      @Param("attemptId") String attemptId,
                      @Param("terminalAt") LocalDateTime terminalAt,
                      @Param("deliveryResultJson") String deliveryResultJson,
                      @Param("diagnosticTraceJson") String diagnosticTraceJson,
                      @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE fulfillment_record
            SET status = 'FAILED',
                terminal_at = #{terminalAt},
                delivery_result_json = NULL,
                last_failure_json = #{failureJson},
                last_diagnostic_trace_json = #{diagnosticTraceJson},
                version = version + 1,
                updated_at = NOW(3)
            WHERE fulfillment_id = #{fulfillmentId}
              AND status = 'PROCESSING'
              AND current_attempt_id = #{attemptId}
              AND version = #{expectedVersion}
            """)
    int markFailed(@Param("fulfillmentId") String fulfillmentId,
                   @Param("attemptId") String attemptId,
                   @Param("terminalAt") LocalDateTime terminalAt,
                   @Param("failureJson") String failureJson,
                   @Param("diagnosticTraceJson") String diagnosticTraceJson,
                   @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE fulfillment_record
            SET last_diagnostic_trace_json = #{diagnosticTraceJson},
                version = version + 1,
                updated_at = NOW(3)
            WHERE fulfillment_id = #{fulfillmentId}
              AND status = 'PROCESSING'
              AND current_attempt_id = #{attemptId}
              AND version = #{expectedVersion}
            """)
    int recordLeftProcessing(@Param("fulfillmentId") String fulfillmentId,
                             @Param("attemptId") String attemptId,
                             @Param("diagnosticTraceJson") String diagnosticTraceJson,
                             @Param("expectedVersion") long expectedVersion);
}
