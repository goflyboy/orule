package com.orule.rule.execution.error;

/**
 * RuleSetType.programCode not found (RFC-0040 §16.6 RULE_SET_NOT_FOUND). HTTP 404.
 */
public class RuleSetNotFoundException extends RuleExecutionException {
    public RuleSetNotFoundException(String ruleSetCode) {
        super("RULE_SET_NOT_FOUND", "RuleSet not found: " + ruleSetCode);
    }
}
