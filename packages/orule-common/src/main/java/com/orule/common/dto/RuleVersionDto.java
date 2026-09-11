package com.orule.common.dto;

import java.time.Instant;

public record RuleVersionDto(
    String id, String ruleId, int version, String status,
    String description, String simpleTs, String groovySource,
    String changelog, String createdBy,
    Instant createdAt, Instant publishedAt, Instant retiredAt
) {}
