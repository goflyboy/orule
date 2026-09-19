package com.orule.rule.execution.client;

import java.util.Map;

/**
 * Upstream metadata response from {@code rule-management-service}
 * (RFC-0040 §20.F01-F03 / RFC-0045 §4.4 v2 record).
 *
 * <p>v2 record (RFC-0045) replaces {@code RuleMetadataResponse} v1:
 * <ul>
 *   <li>Adds {@code domainCode} — propagated from {@code RuleSetType.domain.programCode}.</li>
 *   <li>Adds {@code objectTypeCodes} — de-duplicated {@code RuleType.arguments[*].objectTypeCode}
 *       list, used by the execution service to fetch ObjectType metadata via the
 *       {@code GET /api/v1/object-types/by-program-code} endpoint.</li>
 * </ul>
 *
 * <p>Plain record — no Spring / Jackson annotations because the wire format is
 * governed by the upstream service.
 */
public record RuleMetadataResponseV2(
        String ruleCode,
        String ruleTypeCode,
        String executorType,
        String sourceCode,
        java.util.List<String> functionTypeCodes,
        java.util.List<String> excludeFunctionTypeCodes,
        String domainCode,
        java.util.List<String> objectTypeCodes,
        Map<String, Object> extra
) {
}
