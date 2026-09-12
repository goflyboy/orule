package com.orule.common.dto;

import com.orule.common.model.type.Type;

public record UpdateAttributeTypeRequest(
    String name,
    String dataType,
    Type type,                    // 完整 Type 结构（可选更新）
    Boolean required,
    String defaultValue,
    String description
) {}
