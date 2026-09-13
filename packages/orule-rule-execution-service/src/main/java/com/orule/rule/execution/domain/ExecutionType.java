package com.orule.rule.execution.domain;

/**
 * Execution type enum (RFC-0040 §3.3 execution_log.execution_type CHECK constraint).
 *
 * <p>Two legal values:
 * <ul>
 *   <li>{@link #RULE} — synchronous single rule</li>
 *   <li>{@link #RULE_SET} — asynchronous RuleSet batch</li>
 * </ul>
 */
public enum ExecutionType {
    RULE,
    RULE_SET
}
