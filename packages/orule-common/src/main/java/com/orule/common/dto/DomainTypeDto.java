package com.orule.common.dto;

import java.time.Instant;

/**
 * DomainType DTO（RFC-0032 §3.4 重命名）。
 *
 * <p>字段 {@code code} → {@code programCode}，语义清晰（强调"可编程代码"）。
 */
public record DomainTypeDto(
    String id,
    String programCode,
    String name,
    String description,
    String ownerCode,
    Instant createdAt,
    Instant updatedAt
) {}
