package com.orule.rule.execution.domain;

/**
 * Execution log status enum (RFC-0040 §3.3 / §14.2 execution_log.status CHECK constraint).
 *
 * <p>Five legal values:
 * <ul>
 *   <li>{@link #PENDING} — submitted, not started</li>
 *   <li>{@link #RUNNING} — executing</li>
 *   <li>{@link #SUCCESS} — all rules succeeded</li>
 *   <li>{@link #FAILED} — all rules failed / task-level error</li>
 *   <li>{@link #PARTIAL_SUCCESS} — RuleSet partial success (§20.2 semantics)</li>
 * </ul>
 */
public enum ExecutionLogStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    PARTIAL_SUCCESS;

    /**
     * Whether this is a terminal state (no further state transition).
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == PARTIAL_SUCCESS;
    }
}
