package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAttributeTypeRequest(
    @NotBlank String name,
    @NotBlank String dataType,
    boolean required,
    String defaultValue,
    String description
) {}
