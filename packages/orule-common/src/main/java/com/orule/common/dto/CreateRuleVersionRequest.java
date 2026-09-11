package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRuleVersionRequest(
    @NotBlank String ruleId,
    String description,
    String simpleTs,
    String changelog
) {}
