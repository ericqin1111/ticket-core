package com.ticket.core.fulfillment.service;

import com.ticket.core.fulfillment.entity.FulfillmentRecord;
import com.ticket.core.fulfillment.mapper.FulfillmentRecordMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
class FulfillmentServiceTest {

    @Test
    void findByOrderId_delegatesToMapperAndReturnsRecord() {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId("ful-001");
        record.setOrderId("order-001");
        record.setStatus("PENDING");
        AtomicReference<String> invokedMethod = new AtomicReference<>();
        FulfillmentRecordMapper mapper = mapperProxy(invokedMethod, record, null);
        FulfillmentService fulfillmentService = new FulfillmentService(mapper);

        FulfillmentRecord result = fulfillmentService.findByOrderId("order-001");

        assertThat(result).isSameAs(record);
        assertThat(invokedMethod.get()).isEqualTo("selectOne");
    }

    @Test
    void create_insertsProvidedFulfillmentRecord() {
        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId("ful-002");
        record.setOrderId("order-002");
        record.setStatus("PENDING");
        AtomicReference<FulfillmentRecord> insertedRecord = new AtomicReference<>();
        FulfillmentRecordMapper mapper = mapperProxy(new AtomicReference<>(), null, insertedRecord);
        FulfillmentService fulfillmentService = new FulfillmentService(mapper);

        fulfillmentService.create(record);

        assertThat(insertedRecord.get()).isNotNull();
        assertThat(insertedRecord.get().getFulfillmentId()).isEqualTo("ful-002");
        assertThat(insertedRecord.get().getOrderId()).isEqualTo("order-002");
        assertThat(insertedRecord.get().getStatus()).isEqualTo("PENDING");
    }

    private FulfillmentRecordMapper mapperProxy(AtomicReference<String> invokedMethod,
                                                FulfillmentRecord selectOneResult,
                                                AtomicReference<FulfillmentRecord> insertedRecord) {
        return (FulfillmentRecordMapper) Proxy.newProxyInstance(
                FulfillmentRecordMapper.class.getClassLoader(),
                new Class[]{FulfillmentRecordMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        invokedMethod.set("selectOne");
                        return selectOneResult;
                    }
                    if ("insert".equals(method.getName())) {
                        insertedRecord.set((FulfillmentRecord) args[0]);
                        return 1;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(method.getName())) {
                        return "FulfillmentRecordMapperProxy";
                    }
                    return null;
                });
    }
}
