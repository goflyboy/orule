package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;

/**
 * SPI interface for single rule execution (RFC-0040 §3.7).
 *
 * <p>Implementations are discovered via {@code META-INF/services/}
 * and registered with {@link ExecutorRegistry}.
 *
 * <p>This interface intentionally does NOT extend Spring's {@link org.springframework.stereotype.Component}
 * — registration is handled exclusively through the JDK ServiceLoader mechanism in
 * {@code META-INF/services/com.orule.rule.execution.execution.RuleExecutor}.
 *
 * <p>Note: the synchronous execution path is stateless at the SPI level;
 * the caller ({@code RuleExecutorService}) is responsible for logging, transaction
 * boundaries, and timeout enforcement.
 *
 * @see RuleSetExecutor
 * @see ExecutorRegistry
 */
public interface RuleExecutor {

    /**
     * Returns the canonical executor type identifier used in SPI lookup
     * and stored in {@code execution_log.executor_type}.
     *
     * <p>Corresponds to {@link ExecutorType#typeId()}.
     */
    String executorType();

    /**
     * Execute a single rule.
     *
     * @param input non-null input envelope containing source code and execution context
     * @return execution output (never null; on failure return {@code success=false} with error details)
     * @throws com.orule.rule.execution.error.RuleEvalException    if the source fails to compile
     * @throws com.orule.rule.execution.error.RuleRuntimeException if the compiled script throws at runtime
     */
    ExecutionOutput execute(ExecutionInput input);
}
