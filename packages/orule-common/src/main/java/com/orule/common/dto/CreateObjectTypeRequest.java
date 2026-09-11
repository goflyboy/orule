package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateObjectTypeRequest(
    @NotBlank String domainId,
    @NotBlank String code,
    @NotBlank String name,
    String description
) {}
