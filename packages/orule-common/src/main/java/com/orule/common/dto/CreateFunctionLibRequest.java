package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateFunctionLibRequest(
    @NotBlank String code,
    @NotBlank String name,
    @NotNull FunctionSignature signature,
    String description,
    String category,
    boolean builtin
) {}
