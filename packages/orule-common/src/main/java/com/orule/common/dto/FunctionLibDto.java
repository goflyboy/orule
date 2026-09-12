package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;

/**
 * FunctionLib DTO（RFC-0031 §3.4）。
 *
 * <p>{@code signature} 是函数签名（参数 + 返回类型的 Type 树）。
 */
public record FunctionLibDto(
    String id,
    String code,
    String name,
    FunctionSignature signature,
    String description,
    String category,
    boolean builtin
) {}
