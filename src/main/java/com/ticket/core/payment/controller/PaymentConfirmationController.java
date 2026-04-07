package com.ticket.core.payment.controller;

import com.ticket.core.payment.dto.PaymentConfirmationRequest;
import com.ticket.core.payment.dto.PaymentConfirmationResponse;
import com.ticket.core.payment.service.PaymentConfirmationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/confirmations")
@RequiredArgsConstructor
public class PaymentConfirmationController {

    private final PaymentConfirmationService paymentConfirmationService;

    @PostMapping
    public ResponseEntity<PaymentConfirmationResponse> confirmPayment(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentConfirmationRequest request) {
        return ResponseEntity.ok(paymentConfirmationService.confirmPayment(idempotencyKey, request));
    }
}
