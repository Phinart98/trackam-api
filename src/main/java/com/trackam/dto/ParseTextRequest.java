package com.trackam.dto;

import jakarta.validation.constraints.NotBlank;

public record ParseTextRequest(
    @NotBlank String text,
    String currency // optional, defaults to user's profile currency
) {}
