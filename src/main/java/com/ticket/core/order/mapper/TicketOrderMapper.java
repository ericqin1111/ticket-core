package com.ticket.core.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.order.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {
}
