package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.MetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/domain-types")
@RequiredArgsConstructor
public class DomainTypeController {

    private final MetadataService service;

    @GetMapping
    public Result<List<DomainTypeDto>> list() {
        return Result.success(service.findAllDomainTypes());
    }

    @GetMapping("/{id}")
    public Result<DomainTypeDto> get(@PathVariable String id) {
        return Result.success(service.findDomainType(id));
    }

    @GetMapping("/by-code/{code}")
    public Result<DomainTypeDto> getByCode(@PathVariable String code) {
        return Result.success(service.findDomainTypeByCode(code));
    }

    @PostMapping
    public Result<DomainTypeDto> create(@Valid @RequestBody CreateDomainTypeRequest req) {
        return Result.success(service.createDomainType(req));
    }

    @PutMapping("/{id}")
    public Result<DomainTypeDto> update(@PathVariable String id, @Valid @RequestBody UpdateDomainTypeRequest req) {
        return Result.success(service.updateDomainType(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.deleteDomainType(id);
        return Result.success();
    }
}
