package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.MetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/attribute-types")
@RequiredArgsConstructor
public class AttributeTypeController {

    private final MetadataService service;

    @GetMapping
    public Result<List<AttributeTypeDto>> list(@RequestParam String objectId) {
        return Result.success(service.findAttributesByObject(objectId));
    }

    @PostMapping
    public Result<AttributeTypeDto> create(@Valid @RequestBody CreateAttributeTypeRequest req) {
        return Result.success(service.createAttributeType(req));
    }
}
