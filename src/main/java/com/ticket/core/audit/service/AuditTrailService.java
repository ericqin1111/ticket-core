package com.ticket.core.audit.service;

import com.ticket.core.audit.entity.AuditTrailEvent;
import com.ticket.core.audit.mapper.AuditTrailEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditTrailService {

    private final AuditTrailEventMapper auditTrailEventMapper;

    public void append(AuditTrailEvent event) {
        auditTrailEventMapper.insert(event);
    }
}
