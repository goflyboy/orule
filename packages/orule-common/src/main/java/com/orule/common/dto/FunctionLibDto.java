package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;

/**
 * FunctionLib DTO（RFC-0032 §3.4 重命名）。
 *
 * <p>字段 {@code code} → {@code programCode}。signature 保持 JSON（沿用 RFC-0031，TD-002）。
 */
public record FunctionLibDto(
    String id,
    String programCode,
    String name,
    FunctionSignature signature,
    String description,
    String category,
    boolean builtin
) {}
