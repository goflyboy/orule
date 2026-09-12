package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;

public record UpdateFunctionLibRequest(
    String name,
    FunctionSignature signature,
    String description,
    String category,
    Boolean builtin
) {}
