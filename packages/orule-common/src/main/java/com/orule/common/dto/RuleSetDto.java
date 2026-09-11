package com.orule.common.dto;

import java.time.Instant;
import java.util.List;

public record RuleSetDto(
    String id, String code, String name, String description,
    String domainId, String ownerCode, String status,
    Instant createdAt, Instant updatedAt,
    List<RuleDto> rules
) {}
