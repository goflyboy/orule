package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.FuntionTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/funtion-types")
@RequiredArgsConstructor
public class FuntionTypeController {

    private final FuntionTypeService service;

    @GetMapping
    public Result<List<FuntionTypeDto>> list(@RequestParam(required = false) String category) {
        return Result.success(category == null ? service.findAll() : service.findByCategory(category));
    }

    @GetMapping("/{id}")
    public Result<FuntionTypeDto> get(@PathVariable String id) {
        return Result.success(service.findById(id));
    }

    @PostMapping
    public Result<FuntionTypeDto> create(@Valid @RequestBody CreateFuntionTypeRequest req) {
        return Result.success(service.create(req));
    }

    @PutMapping("/{id}")
    public Result<FuntionTypeDto> update(@PathVariable String id, @Valid @RequestBody UpdateFuntionTypeRequest req) {
        return Result.success(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.success();
    }
}
