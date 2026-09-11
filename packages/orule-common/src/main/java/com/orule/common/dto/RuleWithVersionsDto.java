package com.orule.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * RFC-0016 §3.1 — Detail DTO for GET /api/v1/rules/{id}/with-versions.
 * Includes all versions of the rule inline.
 */
public record RuleWithVersionsDto(
    String id,
    String ruleSetId,
    String code,
    String name,
    String description,
    int sortOrder,
    String ownerCode,
    Instant createdAt,
    Instant updatedAt,
    List<RuleVersionDto> versions
) {}
