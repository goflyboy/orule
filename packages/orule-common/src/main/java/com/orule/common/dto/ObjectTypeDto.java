package com.orule.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * ObjectType DTO（RFC-0032 §3.4）。
 *
 * <p>同时承载 CLASS（普通对象）与 ENUM（枚举）：
 * <ul>
 *   <li>{@code kind}：CLASS | ENUM</li>
 *   <li>{@code enumValues}：仅 kind=ENUM 时使用</li>
 *   <li>{@code attributes}：仅 kind=CLASS 时使用</li>
 * </ul>
 */
public record ObjectTypeDto(
    String id,
    String domainId,
    String programCode,
    String name,
    String kind,                     // CLASS | ENUM
    List<AttributeTypeDto> attributes,
    List<EnumValueDto> enumValues,   // 仅 kind=ENUM
    String description,
    Instant createdAt,
    Instant updatedAt
) {
    /** 内联 enum 值（{@code {code, label, sortOrder}}）。 */
    public record EnumValueDto(String code, String label, Integer sortOrder) {}
}
