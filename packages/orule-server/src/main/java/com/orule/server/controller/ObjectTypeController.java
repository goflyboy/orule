package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.MetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/object-types")
@RequiredArgsConstructor
public class ObjectTypeController {

    private final MetadataService service;

    @GetMapping
    public Result<List<ObjectTypeDto>> list(@RequestParam(required = false) String domainId) {
        return Result.success(domainId == null
            ? service.findAllObjectTypes()
            : service.findObjectTypesByDomain(domainId));
    }

    @GetMapping("/{id}")
    public Result<ObjectTypeDto> get(@PathVariable String id) {
        return Result.success(service.findObjectType(id));
    }

    @GetMapping("/{id}/with-attributes")
    public Result<ObjectTypeDto> getWithAttrs(@PathVariable String id) {
        return Result.success(service.findObjectTypeWithAttributes(id));
    }

    /** RFC-0045 §4.3: 按 (domainCode, programCode) 复合键拉 ObjectType + attributes + enumValues。 */
    @GetMapping("/by-program-code")
    public Result<ObjectTypeDto> byProgramCode(@RequestParam String domainCode,
                                               @RequestParam String programCode) {
        return Result.success(service.findObjectTypeWithAttributesByProgramCode(domainCode, programCode));
    }

    @PostMapping
    public Result<ObjectTypeDto> create(@Valid @RequestBody CreateObjectTypeRequest req) {
        return Result.success(service.createObjectType(req));
    }

    @PutMapping("/{id}")
    public Result<ObjectTypeDto> update(@PathVariable String id, @Valid @RequestBody UpdateObjectTypeRequest req) {
        return Result.success(service.updateObjectType(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.deleteObjectType(id);
        return Result.success();
    }
}
