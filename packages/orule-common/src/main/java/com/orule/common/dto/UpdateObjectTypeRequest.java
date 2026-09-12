package com.orule.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateObjectTypeRequest(
    @NotBlank String name,
    String kind,                       // CLASS | ENUM（可改）
    String description
) {}
