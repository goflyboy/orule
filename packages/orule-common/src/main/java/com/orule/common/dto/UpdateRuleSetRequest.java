package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateRuleSetRequest(
    @NotBlank String name,
    String description,
    String ownerCode
) {}
