package com.orule.common.dto;

import com.orule.common.model.type.FunctionSignature;

public record UpdateFuntionTypeRequest(
    String name,
    FunctionSignature signature,
    String description,
    String category,
    Boolean builtin
) {}
