package com.orule.server.controller;

import com.orule.common.dto.*;
import com.orule.common.dto.Result;
import com.orule.server.service.RuleDomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RuleVersionController {

    private final RuleDomainService service;

    @GetMapping("/rules/{ruleId}/versions")
    public Result<List<RuleVersionDto>> listByRule(@PathVariable String ruleId) {
        return Result.success(service.findVersionsByRule(ruleId));
    }

    /**
     * RFC-0016 §3.1 — Latest version (highest version number) of a rule.
     */
    @GetMapping("/rules/{ruleId}/versions/latest")
    public Result<RuleVersionDto> latest(@PathVariable String ruleId) {
        return Result.success(service.findLatestVersion(ruleId));
    }

    @GetMapping("/rule-versions/{id}")
    public Result<RuleVersionDto> get(@PathVariable String id) {
        return Result.success(service.findVersion(id));
    }

    @PostMapping("/rule-versions")
    public Result<RuleVersionDto> create(
            @Valid @RequestBody CreateRuleVersionRequest req,
            @RequestParam(required = false) String createdBy) {
        return Result.success(service.createVersion(req, createdBy));
    }

    @PutMapping("/rule-versions/{id}")
    public Result<RuleVersionDto> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateRuleVersionRequest req) {
        return Result.success(service.updateVersion(id, req));
    }

    @PostMapping("/rule-versions/{id}/publish")
    public Result<RuleVersionDto> publish(@PathVariable String id) {
        return Result.success(service.publishVersion(id));
    }

    @PostMapping("/rule-versions/{id}/retire")
    public Result<RuleVersionDto> retire(@PathVariable String id) {
        return Result.success(service.retireVersion(id));
    }

    @PostMapping("/rule-versions/{id}/clone")
    public Result<RuleVersionDto> clone(
            @PathVariable String id,
            @Valid @RequestBody CloneRuleVersionRequest req) {
        return Result.success(service.cloneVersion(id, req));
    }

    @DeleteMapping("/rule-versions/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.deleteVersion(id);
        return Result.success();
    }
}
