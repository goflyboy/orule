package com.orule.common.dto;

public record EnumValueDto(
    String id,
    String enumId,
    String code,
    String name,
    int sortOrder
) {}
