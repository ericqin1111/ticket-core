package com.ticket.core.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BuyerDto {

    @NotBlank
    private String buyerRef;
    private String contactPhone;
    private String contactEmail;
}
