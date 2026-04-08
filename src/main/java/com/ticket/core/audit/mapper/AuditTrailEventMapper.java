package com.ticket.core.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.audit.entity.AuditTrailEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditTrailEventMapper extends BaseMapper<AuditTrailEvent> {
}
