package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateEnumTypeRequest(
    @NotBlank String code,
    @NotBlank String name,
    String description
) {}
