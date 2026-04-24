package com.ticket.core.fulfillment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.fulfillment.entity.FulfillmentGovernanceAuditRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FulfillmentGovernanceAuditRecordMapper extends BaseMapper<FulfillmentGovernanceAuditRecordEntity> {

    @Select("""
            SELECT audit_id, fulfillment_id, attempt_id, action_type, from_status, to_status, failure_category,
                   reason_code, retry_budget_snapshot_json, actor_type, occurred_at, created_at
            FROM fulfillment_governance_audit_record
            WHERE fulfillment_id = #{fulfillmentId}
            ORDER BY occurred_at DESC, audit_id DESC
            LIMIT #{limit}
            """)
    List<FulfillmentGovernanceAuditRecordEntity> selectRecentByFulfillmentId(@Param("fulfillmentId") String fulfillmentId,
                                                                             @Param("limit") int limit);
}
