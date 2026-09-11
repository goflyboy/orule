package com.orule.common.dto;

import java.time.Instant;

public record FunctionLibDto(
    String id,
    String code,
    String name,
    String signature,
    String description,
    String category,
    boolean builtin
) {}
