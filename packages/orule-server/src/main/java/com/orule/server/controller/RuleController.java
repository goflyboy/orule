package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.RuleDomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleDomainService service;

    @GetMapping
    public Result<List<RuleDto>> list(@RequestParam String ruleSetId) {
        return Result.success(service.findRulesByRuleSet(ruleSetId));
    }

    @GetMapping("/{id}")
    public Result<RuleDto> get(@PathVariable String id) {
        return Result.success(service.findRule(id));
    }

    /**
     * RFC-0016 §3.1 — Detail with all rule versions inline.
     */
    @GetMapping("/{id}/with-versions")
    public Result<RuleWithVersionsDto> getWithVersions(@PathVariable String id) {
        return Result.success(service.findRuleWithVersions(id));
    }

    @PostMapping
    public Result<RuleDto> create(@Valid @RequestBody CreateRuleRequest req) {
        return Result.success(service.createRule(req));
    }

    @PutMapping("/{id}")
    public Result<RuleDto> update(@PathVariable String id, @Valid @RequestBody UpdateRuleRequest req) {
        return Result.success(service.updateRule(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.deleteRule(id);
        return Result.success();
    }
}
