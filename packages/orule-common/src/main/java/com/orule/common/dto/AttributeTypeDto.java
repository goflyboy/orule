package com.orule.common.dto;

import com.orule.common.model.type.Type;

/**
 * AttributeType DTO（RFC-0031 §3.4）。
 *
 * <p>{@code dataType} 是 kind 标签；{@code type} 是完整 Type 树。
 */
public record AttributeTypeDto(
    String id,
    String objectId,
    String code,
    String name,
    String dataType,          // kind: primitive|enum|object|list|map
    Type type,                // 完整 Type 树
    boolean required,
    String defaultValue,
    String description
) {}
