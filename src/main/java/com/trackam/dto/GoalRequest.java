package com.trackam.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record GoalRequest(
    @NotBlank @Size(max = 200) String name,
    @NotNull @DecimalMin("0.01") BigDecimal targetAmount,
    @NotNull @DecimalMin("0.00") BigDecimal currentAmount,
    @NotBlank @Pattern(regexp = "[A-Z]{3,4}") String currency,
    @NotBlank @Size(max = 100) String icon,
    @NotBlank @Size(max = 50) String color,
    @NotBlank @Size(max = 50) String bgColor,
    @NotBlank @Size(max = 50) String dotColor,
    String deadline  // ISO date string "yyyy-MM-dd", optional
) {}
