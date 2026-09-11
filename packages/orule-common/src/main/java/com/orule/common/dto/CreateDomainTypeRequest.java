package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDomainTypeRequest(
    @NotBlank String code,
    @NotBlank String name,
    String description,
    String ownerCode
) {}
