package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 更新 FunctionLib 请求 DTO（RFC-0031 §3.4）。
 *
 * <p>signature 字段类型从 String 改为 FunctionSignature（Type 树 JSON）。
 */
public record UpdateFunctionLibRequest(
    @NotBlank String name,
    @NotNull FunctionSignature signature,
    String description,
    String category,
    boolean builtin
) {}
