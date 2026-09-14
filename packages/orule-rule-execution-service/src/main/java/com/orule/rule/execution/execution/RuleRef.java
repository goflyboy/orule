package com.orule.rule.execution.execution;

/**
 * Reference to a single rule within a rule-set (RFC-0040 §3.7 / RFC-0021 RuleRef).
 *
 * <p>RFC-0021 is not yet implemented; this record holds the minimum shape needed
 * by {@link RuleSetArtifact}. When RFC-0021 lands, replace with the actual type.
 *
 * @param ruleCode the RuleType.programCode of this rule
 * @param displayOrder execution order within the rule-set (1-based)
 */
public record RuleRef(
        String ruleCode,
        int displayOrder
) {
}
