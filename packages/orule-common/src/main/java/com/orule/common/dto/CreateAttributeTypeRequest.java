package com.orule.common.dto;

import com.orule.common.model.type.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAttributeTypeRequest(
    @NotBlank String objectId,
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String dataType,    // primitive|enum|object|list|map
    @NotNull  Type type,          // 完整 Type 结构（必填）
    Boolean required,
    String defaultValue,
    String description
) {}
