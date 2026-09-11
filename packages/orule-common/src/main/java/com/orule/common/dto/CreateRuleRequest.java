package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRuleRequest(
    @NotBlank String ruleSetId,
    @NotBlank String code,
    @NotBlank String name,
    String description,
    int sortOrder,
    String ownerCode
) {}
