package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.RuleDomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rule-sets")
@RequiredArgsConstructor
public class RuleSetController {

    private final RuleDomainService service;

    @GetMapping
    public Result<List<RuleSetDto>> list() {
        return Result.success(service.findAllRuleSets());
    }

    @GetMapping("/{id}")
    public Result<RuleSetDto> get(@PathVariable String id) {
        return Result.success(service.findRuleSet(id));
    }

    @GetMapping("/{id}/with-rules")
    public Result<RuleSetDto> getWithRules(@PathVariable String id) {
        return Result.success(service.findRuleSetWithRules(id));
    }

    @PostMapping
    public Result<RuleSetDto> create(@Valid @RequestBody CreateRuleSetRequest req) {
        return Result.success(service.createRuleSet(req));
    }

    @PutMapping("/{id}")
    public Result<RuleSetDto> update(@PathVariable String id, @Valid @RequestBody UpdateRuleSetRequest req) {
        return Result.success(service.updateRuleSet(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.deleteRuleSet(id);
        return Result.success();
    }
}
