package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAttributeTypeRequest(
    @NotBlank String objectId,
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String dataType,
    boolean required,
    String defaultValue,
    String description
) {}
