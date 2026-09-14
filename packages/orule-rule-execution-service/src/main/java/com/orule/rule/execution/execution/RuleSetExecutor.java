package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;

/**
 * SPI interface for rule-set batch execution (RFC-0040 §3.7).
 *
 * <p>Unlike {@link RuleExecutor} which is synchronous and returns immediately,
 * a {@link RuleSetExecutor} runs asynchronously (see {@link AsyncRuleSetExecutor})
 * and is invoked by the {@code @Async} worker registered in
 * {@link ExecutorRegistry#submitRuleSet(String, java.util.Map, RuleSetArtifact)}.
 *
 * <p>Implementations are discovered via {@code META-INF/services/}.
 *
 * @see RuleExecutor
 * @see ExecutorRegistry
 */
public interface RuleSetExecutor {

    /**
     * Returns the canonical executor type identifier for this rule-set executor.
     * All rules within the set are executed using this executor type unless
     * overridden per-rule by the artifact.
     */
    String executorType();

    /**
     * Execute a single rule within the context of a rule-set.
     *
     * <p>The {@code input.context} contains the accumulated output context from
     * previously executed rules in the set (Q1/Q2 semantics: partial success
     * preserves mutations, see RFC-0040 §12.1).
     *
     * @param input non-null input envelope containing rule source code and current accumulated context
     * @return execution output (never null; on failure return {@code success=false})
     */
    ExecutionOutput execute(ExecutionInput input);
}
