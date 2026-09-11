package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateFunctionLibRequest(
    @NotBlank String name,
    @NotBlank String signature,
    String description,
    String category,
    boolean builtin
) {}
