package com.orule.rule.execution.systemtest.support;

import com.orule.rule.execution.domain.ExecutionLog;

/**
 * Lambda-based assertion surface for a {@link ExecutionLog} row.
 *
 * <p>Use inside {@link RuleExecutionResult#log(LogAssert)}:
 *
 * <pre>{@code
 * result.log(row -> {
 *   assertThat(row.getStatus().name(), is("SUCCESS"));
 *   assertThat(row.getRuleCode(), is("X"));
 * });
 * }</pre>
 */
@FunctionalInterface
public interface LogAssert {
    void check(ExecutionLog row, String taskId);
}
