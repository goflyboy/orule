package com.orule.server.service;

import com.orule.common.dto.*;
import com.orule.common.entity.*;
import com.orule.common.exception.ConflictException;
import com.orule.common.exception.NotFoundException;
import com.orule.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleDomainService {

    private final RuleSetRepository ruleSetRepo;
    private final RuleRepository ruleRepo;
    private final RuleVersionRepository versionRepo;
    private final DomainTypeRepository domainRepo;

    // === RuleSet ===

    public List<RuleSetDto> findAllRuleSets() {
        return ruleSetRepo.findAll().stream().map(this::toRuleSetDto).toList();
    }

    public RuleSetDto findRuleSet(String id) {
        return toRuleSetDto(ruleSetRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleSet", id)));
    }

    @Transactional
    public RuleSetDto findRuleSetWithRules(String id) {
        RuleSet e = ruleSetRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleSet", id));
        List<RuleDto> rules = ruleRepo.findByRuleSetId(id).stream()
            .map(this::toRuleDto).toList();
        return new RuleSetDto(
            e.getId(), e.getCode(), e.getName(), e.getDescription(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getOwnerCode(), e.getStatus(),
            e.getCreatedAt(), e.getUpdatedAt(), rules);
    }

    @Transactional
    public RuleSetDto createRuleSet(CreateRuleSetRequest req) {
        DomainType domain = domainRepo.findById(req.domainId())
            .orElseThrow(() -> new NotFoundException("DomainType", req.domainId()));
        RuleSet e = RuleSet.builder()
            .id(UUID.randomUUID().toString())
            .code(req.code()).name(req.name())
            .description(req.description()).domain(domain)
            .ownerCode(req.ownerCode()).status("MAINTENANCE").build();
        return toRuleSetDto(ruleSetRepo.save(e));
    }

    @Transactional
    public RuleSetDto updateRuleSet(String id, UpdateRuleSetRequest req) {
        RuleSet e = ruleSetRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleSet", id));
        e.setName(req.name());
        e.setDescription(req.description());
        e.setOwnerCode(req.ownerCode());
        return toRuleSetDto(ruleSetRepo.save(e));
    }

    @Transactional
    public void deleteRuleSet(String id) {
        if (!ruleSetRepo.existsById(id)) throw new NotFoundException("RuleSet", id);
        ruleSetRepo.deleteById(id);
    }

    // === Rule ===

    public List<RuleDto> findRulesByRuleSet(String ruleSetId) {
        return ruleRepo.findByRuleSetId(ruleSetId).stream().map(this::toRuleDto).toList();
    }

    public RuleDto findRule(String id) {
        return toRuleDto(ruleRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("Rule", id)));
    }

    @Transactional
    public RuleDto createRule(CreateRuleRequest req) {
        RuleSet rs = ruleSetRepo.findById(req.ruleSetId())
            .orElseThrow(() -> new NotFoundException("RuleSet", req.ruleSetId()));
        Rule e = Rule.builder()
            .id(UUID.randomUUID().toString())
            .ruleSet(rs).code(req.code()).name(req.name())
            .description(req.description())
            .sortOrder(req.sortOrder()).ownerCode(req.ownerCode()).build();
        return toRuleDto(ruleRepo.save(e));
    }

    @Transactional
    public RuleDto updateRule(String id, UpdateRuleRequest req) {
        Rule e = ruleRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("Rule", id));
        e.setName(req.name());
        e.setDescription(req.description());
        e.setSortOrder(req.sortOrder());
        e.setOwnerCode(req.ownerCode());
        return toRuleDto(ruleRepo.save(e));
    }

    @Transactional
    public RuleWithVersionsDto findRuleWithVersions(String id) {
        Rule e = ruleRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("Rule", id));
        List<RuleVersionDto> versions = versionRepo.findByRuleIdOrderByVersionDesc(id).stream()
            .map(this::toVersionDto).toList();
        return new RuleWithVersionsDto(
            e.getId(),
            e.getRuleSet() != null ? e.getRuleSet().getId() : null,
            e.getCode(), e.getName(), e.getDescription(),
            e.getSortOrder(), e.getOwnerCode(),
            e.getCreatedAt(), e.getUpdatedAt(), versions);
    }

    @Transactional
    public RuleVersionDto findLatestVersion(String ruleId) {
        return versionRepo.findByRuleIdOrderByVersionDesc(ruleId).stream()
            .findFirst()
            .map(this::toVersionDto)
            .orElseThrow(() -> new NotFoundException("RuleVersion", "rule=" + ruleId));
    }

    @Transactional
    public void deleteRule(String id) {
        if (!ruleRepo.existsById(id)) throw new NotFoundException("Rule", id);
        ruleRepo.deleteById(id);
    }

    // === RuleVersion ===

    public List<RuleVersionDto> findVersionsByRule(String ruleId) {
        return versionRepo.findByRuleId(ruleId).stream().map(this::toVersionDto).toList();
    }

    public RuleVersionDto findVersion(String id) {
        return toVersionDto(versionRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleVersion", id)));
    }

    @Transactional
    public RuleVersionDto createVersion(CreateRuleVersionRequest req, String createdBy) {
        Rule rule = ruleRepo.findById(req.ruleId())
            .orElseThrow(() -> new NotFoundException("Rule", req.ruleId()));
        Integer maxVer = versionRepo.findMaxVersionByRuleId(req.ruleId());
        int nextVer = (maxVer != null ? maxVer : 0) + 1;
        RuleVersion e = RuleVersion.builder()
            .id(UUID.randomUUID().toString())
            .rule(rule).version(nextVer)
            .status("MAINTENANCE")
            .description(req.description())
            .simpleTs(req.simpleTs())
            .changelog(req.changelog())
            .createdBy(createdBy).build();
        return toVersionDto(versionRepo.save(e));
    }

    @Transactional
    public RuleVersionDto updateVersion(String id, UpdateRuleVersionRequest req) {
        RuleVersion e = versionRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleVersion", id));
        if (!"MAINTENANCE".equals(e.getStatus())) {
            throw new ConflictException("Only MAINTENANCE versions can be edited: " + e.getStatus());
        }
        e.setDescription(req.description());
        e.setSimpleTs(req.simpleTs());
        e.setChangelog(req.changelog());
        return toVersionDto(versionRepo.save(e));
    }

    @Transactional
    public RuleVersionDto publishVersion(String id) {
        RuleVersion e = versionRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleVersion", id));
        if (!"MAINTENANCE".equals(e.getStatus())) {
            throw new ConflictException("Only MAINTENANCE can publish: " + e.getStatus());
        }
        if (e.getSimpleTs() == null || e.getSimpleTs().isBlank()) {
            throw new ConflictException("simpleTs is empty, cannot publish");
        }
        // Auto-retire current PUBLISHED version
        versionRepo.findByRuleIdAndStatus(e.getRule().getId(), "PUBLISHED")
            .ifPresent(old -> {
                old.setStatus("RETIRED");
                old.setRetiredAt(Instant.now());
                versionRepo.save(old);
            });
        e.setStatus("PUBLISHED");
        e.setPublishedAt(Instant.now());
        return toVersionDto(versionRepo.save(e));
    }

    @Transactional
    public RuleVersionDto retireVersion(String id) {
        RuleVersion e = versionRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("RuleVersion", id));
        if (!"PUBLISHED".equals(e.getStatus())) {
            throw new ConflictException("Only PUBLISHED can retire: " + e.getStatus());
        }
        e.setStatus("RETIRED");
        e.setRetiredAt(Instant.now());
        return toVersionDto(versionRepo.save(e));
    }

    @Transactional
    public RuleVersionDto cloneVersion(String sourceId, CloneRuleVersionRequest req) {
        RuleVersion src = versionRepo.findById(sourceId)
            .orElseThrow(() -> new NotFoundException("RuleVersion", sourceId));
        Integer maxVer = versionRepo.findMaxVersionByRuleId(src.getRule().getId());
        int nextVer = (maxVer != null ? maxVer : 0) + 1;
        RuleVersion e = RuleVersion.builder()
            .id(UUID.randomUUID().toString())
            .rule(src.getRule())
            .version(nextVer)
            .status("MAINTENANCE")
            .description("Cloned from v" + src.getVersion())
            .simpleTs(src.getSimpleTs())
            .groovySource(src.getGroovySource())
            .changelog(req.changelog())
            .createdBy(req.createdBy()).build();
        return toVersionDto(versionRepo.save(e));
    }

    @Transactional
    public void deleteVersion(String id) {
        if (!versionRepo.existsById(id)) throw new NotFoundException("RuleVersion", id);
        versionRepo.deleteById(id);
    }

    // === DTO conversions ===

    private RuleSetDto toRuleSetDto(RuleSet e) {
        return new RuleSetDto(e.getId(), e.getCode(), e.getName(), e.getDescription(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getOwnerCode(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt(), null);
    }

    private RuleSetDto toRuleSetDtoWithRules(RuleSet e) {
        List<RuleDto> rules = e.getRules() != null
            ? e.getRules().stream().map(this::toRuleDto).toList()
            : List.of();
        return new RuleSetDto(e.getId(), e.getCode(), e.getName(), e.getDescription(),
            e.getDomain() != null ? e.getDomain().getId() : null,
            e.getOwnerCode(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt(), rules);
    }

    private RuleDto toRuleDto(Rule e) {
        return new RuleDto(e.getId(),
            e.getRuleSet() != null ? e.getRuleSet().getId() : null,
            e.getCode(), e.getName(), e.getDescription(),
            e.getSortOrder(), e.getOwnerCode(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private RuleVersionDto toVersionDto(RuleVersion e) {
        return new RuleVersionDto(e.getId(),
            e.getRule() != null ? e.getRule().getId() : null,
            e.getVersion(), e.getStatus(), e.getDescription(),
            e.getSimpleTs(), e.getGroovySource(), e.getChangelog(),
            e.getCreatedBy(), e.getCreatedAt(), e.getPublishedAt(), e.getRetiredAt());
    }
}
