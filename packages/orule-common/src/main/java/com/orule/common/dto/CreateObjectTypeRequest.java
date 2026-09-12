package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * CreateObjectTypeRequest（RFC-0032 §3.4）。
 *
 * <p>kind=ENUM 时 enumValues 必填。
 */
public record CreateObjectTypeRequest(
    @NotBlank String domainId,
    @NotBlank String programCode,
    @NotBlank String name,
    @NotNull  String kind,                // CLASS | ENUM
    List<ObjectTypeDto.EnumValueDto> enumValues,  // kind=ENUM 时必填
    String description
) {}
