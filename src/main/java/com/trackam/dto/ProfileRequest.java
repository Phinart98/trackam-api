package com.trackam.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ProfileRequest(
    String businessName,
    String ownerName,
    String businessType,
    @NotBlank String currency,
    String country,
    BigDecimal monthlyBudget,
    boolean onboarded
) {}
