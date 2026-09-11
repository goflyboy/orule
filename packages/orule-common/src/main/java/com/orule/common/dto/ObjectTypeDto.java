package com.orule.common.dto;

import java.time.Instant;
import java.util.List;

public record ObjectTypeDto(
    String id,
    String domainId,
    String code,
    String name,
    String description,
    List<AttributeTypeDto> attributes,
    Instant createdAt,
    Instant updatedAt
) {}
