package com.trackam.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransactionRequest(
    @NotBlank @Pattern(regexp = "income|expense", message = "must be 'income' or 'expense'") String type,
    @NotNull @DecimalMin("0.01") @DecimalMax("9999999.9999") BigDecimal amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3,4}", message = "must be a valid currency code") String currency,
    @NotBlank @Size(max = 50) String category,
    @NotBlank @Size(max = 500) String description,
    @Size(max = 200) String vendor,
    @NotBlank @Pattern(regexp = "manual|ai-text|ai-image|ai-voice", message = "must be 'manual', 'ai-text', 'ai-image', or 'ai-voice'") String source,
    @NotBlank String date,
    Integer confidence
) {}
