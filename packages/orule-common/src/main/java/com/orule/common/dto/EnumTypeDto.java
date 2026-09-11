package com.orule.common.dto;

import java.util.List;

public record EnumTypeDto(
    String id,
    String code,
    String name,
    String description,
    List<EnumValueDto> values
) {}
