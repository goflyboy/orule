package com.orule.common.dto;

import java.time.Instant;

public record RuleDto(
    String id,
    String ruleSetId,
    String code,
    String name,
    String description,
    int sortOrder,
    String ownerCode,
    Instant createdAt,
    Instant updatedAt
) {}
