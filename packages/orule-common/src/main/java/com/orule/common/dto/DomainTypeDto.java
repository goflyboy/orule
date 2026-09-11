package com.orule.common.dto;

import java.time.Instant;

public record DomainTypeDto(
    String id,
    String code,
    String name,
    String description,
    String ownerCode,
    Instant createdAt,
    Instant updatedAt
) {}
