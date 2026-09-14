package com.orule.rule.execution.execution;

/**
 * Placeholder for a rule-set execution artifact (RFC-0040 §3.7 / RFC-0021 RuleSetArtifact).
 *
 * <p>RFC-0021 is not yet implemented; this record holds the minimum shape needed
 * by {@link ExecutorRegistry#submitRuleSet(String, java.util.Map, RuleSetArtifact)}.
 * When RFC-0021 lands, replace with the actual artifact type.
 *
 * @param executorType executor type identifier (e.g. "java-source")
 * @param rules ordered list of rule references within this set
 */
public record RuleSetArtifact(
        String executorType,
        java.util.List<RuleRef> rules
) {
}
