package com.ticket.core.audit.service;

import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.mapper.AuditTrailEventMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditTrailServiceTest {

    @Test
    void append_insertsEventIntoMapper() {
        AuditTrailEventMapper mapper = mock(AuditTrailEventMapper.class);
        AuditTrailService service = new AuditTrailService(mapper);
        AuditTrailEvent event = new AuditTrailEvent();
        event.setEventId("evt-001");

        service.append(event);

        verify(mapper).insert(event);
    }
}
