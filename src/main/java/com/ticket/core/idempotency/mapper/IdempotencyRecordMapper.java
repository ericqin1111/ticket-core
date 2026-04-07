package com.ticket.core.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.idempotency.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
}
