package com.trackam.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TransactionRequest(
    @NotBlank String type,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotBlank String currency,
    @NotBlank String category,
    @NotBlank String description,
    String vendor,
    @NotBlank String source,
    @NotBlank String date,
    Integer confidence
) {}
