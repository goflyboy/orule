package com.orule.rule.execution.error;

/**
 * RuleType.programCode not found (RFC-0040 §16.6 RULE_NOT_FOUND). HTTP 404.
 */
public class RuleNotFoundException extends RuleExecutionException {
    public RuleNotFoundException(String ruleCode) {
        super("RULE_NOT_FOUND", "Rule not found: " + ruleCode);
    }
}
