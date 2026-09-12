package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDomainTypeRequest(
    @NotBlank String programCode,
    @NotBlank String name,
    String description,
    String ownerCode
) {}
