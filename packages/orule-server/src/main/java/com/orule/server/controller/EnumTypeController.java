package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.EnumTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enum-types")
@RequiredArgsConstructor
public class EnumTypeController {

    private final EnumTypeService service;

    @GetMapping
    public Result<List<EnumTypeDto>> list() {
        return Result.success(service.findAll());
    }

    @GetMapping("/{id}")
    public Result<EnumTypeDto> get(@PathVariable String id) {
        return Result.success(service.findById(id));
    }

    @GetMapping("/{id}/values")
    public Result<EnumTypeDto> getValues(@PathVariable String id) {
        return Result.success(service.findByIdWithValues(id));
    }

    @PostMapping
    public Result<EnumTypeDto> create(@Valid @RequestBody CreateEnumTypeRequest req) {
        return Result.success(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<EnumTypeDto> update(@PathVariable String id, @Valid @RequestBody UpdateEnumTypeRequest req) {
        return Result.success(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/values")
    public Result<EnumTypeDto> addValue(@PathVariable String id, @Valid @RequestBody CreateEnumValueRequest req) {
        return Result.success(service.addValue(id, req));
    }
}
