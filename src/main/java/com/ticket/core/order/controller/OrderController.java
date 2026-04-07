package com.ticket.core.order.controller;

import com.ticket.core.order.dto.CreateOrderRequest;
import com.ticket.core.order.dto.CreateOrderResponse;
import com.ticket.core.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /orders
     * Atomically consumes an ACTIVE Reservation and creates an Order in PENDING_PAYMENT state.
     * Requires an action-scoped Idempotency-Key header (separate idempotency space from POST /reservations).
     */
    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.createOrder(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
