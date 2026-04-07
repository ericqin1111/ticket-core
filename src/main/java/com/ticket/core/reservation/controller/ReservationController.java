package com.ticket.core.reservation.controller;

import com.ticket.core.reservation.dto.CreateReservationRequest;
import com.ticket.core.reservation.dto.CreateReservationResponse;
import com.ticket.core.reservation.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * POST /reservations
     * Creates a Reservation in ACTIVE state and locks the requested inventory quantity.
     * Requires an action-scoped Idempotency-Key header (not shared with POST /orders).
     */
    @PostMapping
    public ResponseEntity<CreateReservationResponse> createReservation(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {
        CreateReservationResponse response = reservationService.createReservation(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
