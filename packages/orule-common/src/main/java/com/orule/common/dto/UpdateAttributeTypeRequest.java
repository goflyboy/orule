package com.orule.common.dto;

public record UpdateAttributeTypeRequest(
    String name,
    String dataType,
    String subDataTypeProgramCode,
    String subDataTypeProgramCode2,
    Boolean required,
    String defaultValue,
    String description
) {}
