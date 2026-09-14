package com.orule.rule.execution.client;

import java.util.Map;

/**
 * Upstream metadata response from {@code rule-management-service}
 * (RFC-0040 §20.F01-F03 / TASK-1.3.3).
 *
 * <p>Plain record — no Spring / Jackson annotations because the wire format is
 * governed by the upstream service.  Field semantics:
 * <ul>
 *   <li>{@code ruleCode} — same value the client submitted</li>
 *   <li>{@code ruleTypeCode} — resolves {@link com.orule.common.entity.RuleType} whitelist scope (RFC-0020)</li>
 *   <li>{@code executorType} — SPI route key (java-source / python / ...)</li>
 *   <li>{@code sourceCode} — SimpleTS-generated Groovy/Java source</li>
 *   <li>{@code functionTypeCodes} — whitelist for this rule (RFC-0020 §3.3)</li>
 *   <li>{@code excludeFunctionTypeCodes} — blacklist within the whitelist</li>
 * </ul>
 *
 * <p>v0.7 NOTE: this record does NOT depend on a {@code Rule} JPA entity because
 * {@code orule-rule-execution-service} must NOT depend on the rule-management
 * persistence schema (cross-module boundary, see RFC §A).
 */
public record RuleMetadataResponse(
        String ruleCode,
        String ruleTypeCode,
        String executorType,
        String sourceCode,
        java.util.List<String> functionTypeCodes,
        java.util.List<String> excludeFunctionTypeCodes,
        Map<String, Object> extra
) {
}
