package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateEnumTypeRequest(
    @NotBlank String name,
    String description
) {}
