package com.ticket.core.payment.service;

import com.ticket.core.payment.dto.PaymentConfirmationRequest;
import com.ticket.core.payment.dto.PaymentConfirmationResponse;
import org.springframework.stereotype.Service;

@Service
public class PaymentConfirmationService {

    public PaymentConfirmationResponse confirmPayment(String idempotencyKey, PaymentConfirmationRequest request) {
        throw new UnsupportedOperationException("RFC-TKT001-02 Step 2 will implement payment confirmation flow.");
    }
}
