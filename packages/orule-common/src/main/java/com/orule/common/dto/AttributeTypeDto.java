package com.orule.common.dto;

import com.orule.common.model.type.Type;

/**
 * AttributeType DTO（RFC-0032 §3.4 重构）。
 *
 * <p>删除 {@code type} JSON 字段，改为 3 列关系化：
 * <ul>
 *   <li>{@code dataType}：kind 标签</li>
 *   <li>{@code subDataTypeProgramCode}：primitive.name 或 object/list/map 目标 programCode</li>
 *   <li>{@code subDataTypeProgramCode2}：仅 map 使用</li>
 * </ul>
 *
 * <p>{@code type} 由服务端根据 3 列 + ObjectType 表组装（{@code TypeFactory.buildType}）。
 */
public record AttributeTypeDto(
    String id,
    String objectId,
    String programCode,
    String name,
    String dataType,
    String subDataTypeProgramCode,
    String subDataTypeProgramCode2,
    boolean required,
    String defaultValue,
    String description,
    Type type            // 服务端组装
) {}
