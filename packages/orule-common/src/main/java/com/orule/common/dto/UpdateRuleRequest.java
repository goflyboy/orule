package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateRuleRequest(
    @NotBlank String name,
    String description,
    int sortOrder,
    String ownerCode
) {}
