package com.orule.common.dto;

public record AttributeTypeDto(
    String id,
    String objectId,
    String code,
    String name,
    String dataType,
    boolean required,
    String defaultValue,
    String description
) {}
