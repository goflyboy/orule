package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.FunctionLibService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/function-libs")
@RequiredArgsConstructor
public class FunctionLibController {

    private final FunctionLibService service;

    @GetMapping
    public Result<List<FunctionLibDto>> list(@RequestParam(required = false) String category) {
        return Result.success(category == null ? service.findAll() : service.findByCategory(category));
    }

    @GetMapping("/{id}")
    public Result<FunctionLibDto> get(@PathVariable String id) {
        return Result.success(service.findById(id));
    }

    @PostMapping
    public Result<FunctionLibDto> create(@Valid @RequestBody CreateFunctionLibRequest req) {
        return Result.success(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<FunctionLibDto> update(@PathVariable String id, @Valid @RequestBody UpdateFunctionLibRequest req) {
        return Result.success(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.success();
    }
}
