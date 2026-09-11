package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRuleSetRequest(
    @NotBlank String code,
    @NotBlank String name,
    String description,
    @NotBlank String domainId,
    String ownerCode
) {}
