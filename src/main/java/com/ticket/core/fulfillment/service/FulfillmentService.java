package com.ticket.core.fulfillment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final FulfillmentRecordMapper fulfillmentRecordMapper;

    public FulfillmentRecord findByOrderId(String orderId) {
        return fulfillmentRecordMapper.selectOne(new LambdaQueryWrapper<FulfillmentRecord>()
                .eq(FulfillmentRecord::getOrderId, orderId));
    }

    public void create(FulfillmentRecord fulfillmentRecord) {
        fulfillmentRecordMapper.insert(fulfillmentRecord);
    }
}
