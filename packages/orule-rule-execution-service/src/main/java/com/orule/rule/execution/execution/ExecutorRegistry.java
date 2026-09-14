package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.ExecutorNotRegisteredException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPI-based executor registry (RFC-0040 §3.7).
 *
 * <p>Discovers all {@link RuleExecutor} and {@link RuleSetExecutor} implementations
 * via {@link ServiceLoader} on application startup.
 *
 * <p>Thread-safe: reads are lock-free via {@link ConcurrentHashMap}.
 */
@Component
public class ExecutorRegistry {

    private final Map<String, RuleExecutor> ruleExecutors = new ConcurrentHashMap<>(4);
    private final Map<String, RuleSetExecutor> ruleSetExecutors = new ConcurrentHashMap<>(4);

    /**
     * Creates the registry and immediately loads all SPI implementations.
     */
    public ExecutorRegistry() {
        loadRuleExecutors();
        loadRuleSetExecutors();
    }

    // --- RuleExecutor accessors ---

    /**
     * Resolve a {@link RuleExecutor} by its {@link ExecutorType#typeId()}.
     *
     * @param typeId non-null type identifier (e.g. "java-source")
     * @return the registered executor
     * @throws ExecutorNotRegisteredException if no implementation is registered for this type
     */
    public RuleExecutor resolveRuleExecutor(String typeId) {
        RuleExecutor executor = ruleExecutors.get(typeId);
        if (executor == null) {
            throw new ExecutorNotRegisteredException(typeId);
        }
        return executor;
    }

    /**
     * Returns all registered rule executor type IDs.
     */
    public List<String> registeredRuleExecutorTypes() {
        return List.copyOf(ruleExecutors.keySet());
    }

    // --- RuleSetExecutor accessors ---

    /**
     * Resolve a {@link RuleSetExecutor} by its {@link ExecutorType#typeId()}.
     */
    public RuleSetExecutor resolveRuleSetExecutor(String typeId) {
        RuleSetExecutor executor = ruleSetExecutors.get(typeId);
        if (executor == null) {
            throw new ExecutorNotRegisteredException(typeId);
        }
        return executor;
    }

    /**
     * Returns all registered rule-set executor type IDs.
     */
    public List<String> registeredRuleSetExecutorTypes() {
        return List.copyOf(ruleSetExecutors.keySet());
    }

    // --- convenience delegation (used by RuleExecutorService) ---

    /**
     * Convenience: resolve + execute a single rule.
     *
     * @throws ExecutorNotRegisteredException
     * @throws com.orule.rule.execution.error.RuleEvalException
     * @throws com.orule.rule.execution.error.RuleRuntimeException
     */
    public ExecutionOutput executeRule(String executorType, ExecutionInput input) {
        return resolveRuleExecutor(executorType).execute(input);
    }

    // --- test-support (production code should not call this directly) ---

    /**
     * Test-only registration entry point used by unit tests in this module.
     * <strong>NOT for production use.</strong> Production code should rely on the
     * {@link ServiceLoader}-discovered implementations in {@code META-INF/services/}.
     */
    public void registerForTesting(RuleExecutor executor) {
        ruleExecutors.put(executor.executorType(), executor);
    }

    /**
     * Test-only registration entry point used by unit tests in this module.
     * <strong>NOT for production use.</strong>
     */
    public void registerForTesting(RuleSetExecutor executor) {
        ruleSetExecutors.put(executor.executorType(), executor);
    }

    // --- SPI loading ---

    private void loadRuleExecutors() {
        for (RuleExecutor impl : ServiceLoader.load(RuleExecutor.class,
                Thread.currentThread().getContextClassLoader())) {
            String typeId = impl.executorType();
            RuleExecutor existing = ruleExecutors.put(typeId, impl);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate RuleExecutor for typeId='" + typeId
                                + "': " + impl.getClass().getName()
                                + " vs " + existing.getClass().getName());
            }
        }
    }

    private void loadRuleSetExecutors() {
        for (RuleSetExecutor impl : ServiceLoader.load(RuleSetExecutor.class,
                Thread.currentThread().getContextClassLoader())) {
            String typeId = impl.executorType();
            RuleSetExecutor existing = ruleSetExecutors.put(typeId, impl);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate RuleSetExecutor for typeId='" + typeId
                                + "': " + impl.getClass().getName()
                                + " vs " + existing.getClass().getName());
            }
        }
    }
}
