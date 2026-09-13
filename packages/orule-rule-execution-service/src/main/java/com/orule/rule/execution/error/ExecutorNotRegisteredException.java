package com.orule.rule.execution.error;

/**
 * ExecutorType not registered in SPI (RFC-0040 §16.6 EXECUTOR_NOT_REGISTERED). HTTP 500.
 *
 * <p>Common causes:
 * <ul>
 *   <li>SPI file META-INF/services config missing</li>
 *   <li>New Executor added but not repackaged under orule-rule-execution-service classpath</li>
 * </ul>
 */
public class ExecutorNotRegisteredException extends RuleExecutionException {
    public ExecutorNotRegisteredException(String executorType) {
        super("EXECUTOR_NOT_REGISTERED", "No executor registered for type: " + executorType);
    }
}
