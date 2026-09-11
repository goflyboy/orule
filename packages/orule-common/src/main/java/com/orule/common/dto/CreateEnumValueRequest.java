package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEnumValueRequest(
    @NotBlank String code,
    @NotBlank String name,
    int sortOrder
) {}
