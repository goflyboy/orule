package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrator enforcing execution timeout on the SPI layer (RFC-0040 §3.13 + TASK-1.2.5).
 *
 * <p>Wraps {@link RuleExecutor#execute(ExecutionInput)} in a future with a hard timeout
 * to satisfy R-001 (RULE-EXEC-DOS-01).  If the executor thread runs longer than
 * {@code orule.execution.timeout-ms}, we cancel it and surface a {@link RuleRuntimeException}
 * with {@code errorCode = "TIMEOUT"}.
 *
 * <p>The internal pool is bounded (default 32 threads); requests that exceed the queue
 * size raise {@link RuleRuntimeException} with {@code "EXECUTOR_BUSY"} which the
 * GlobalExceptionHandler maps to {@code HTTP 503}.
 *
 * <p>v0.7 NOTE: timeout enforcement runs in a separate thread.  This protects the caller
 * (HTTP request thread) from being blocked, but cannot guarantee JVM-level interruption
 * of malicious code (see RFC-0040 §5 R-001 for the residual risk).
 */
@Service
public class RuleExecutorService {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutorService.class);

    private final ExecutorRegistry registry;
    private final ExecutorService executor;
    private final long defaultTimeoutMs;

    public RuleExecutorService(
            ExecutorRegistry registry,
            @Value("${orule.execution.pool.core-size:16}") int coreSize,
            @Value("${orule.execution.pool.max-size:32}") int maxSize,
            @Value("${orule.execution.pool.queue-capacity:64}") int queueCapacity,
            @Value("${orule.execution.timeout-ms:30000}") long defaultTimeoutMs) {
        this.registry = registry;
        this.defaultTimeoutMs = defaultTimeoutMs;

        // Named thread pool with bounded queue.  AbortPolicy yields a 503 via
        // GlobalExceptionHandler on saturation.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                coreSize, maxSize,
                60L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor = pool;

        log.info("RuleExecutorService initialized: corePool={}, maxPool={}, queue={}, defaultTimeout={}ms",
                coreSize, maxSize, queueCapacity, defaultTimeoutMs);
    }

    /**
     * Execute with the default timeout ({@code orule.execution.timeout-ms}).
     */
    public ExecutionOutput execute(String executorType, ExecutionInput input) {
        return execute(executorType, input, defaultTimeoutMs);
    }

    /**
     * Execute with a per-call timeout.
     *
     * @throws RuleEvalException    on compile failure or sandbox violation
     * @throws RuleRuntimeException on timeout, executor busy, or runtime error
     */
    public ExecutionOutput execute(String executorType, ExecutionInput input, long timeoutMs) {
        if (executorType == null || executorType.isBlank()) {
            throw new IllegalArgumentException("executorType must not be blank");
        }
        final RuleExecutor impl = registry.resolveRuleExecutor(executorType);
        log.debug("Executing rule: type={}, timeoutMs={}", executorType, timeoutMs);

        Callable<ExecutionOutput> task = () -> impl.execute(input);

        Future<ExecutionOutput> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException ree) {
            log.warn("Executor pool saturated: type={}", executorType);
            throw new RuleRuntimeException("Executor busy: " + ree.getMessage(), "EXECUTOR_BUSY", ree);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("Rule execution timeout after {}ms: type={}", timeoutMs, executorType);
            throw new RuleRuntimeException(
                    "Rule execution exceeded timeout " + timeoutMs + "ms", "TIMEOUT", te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuleRuntimeException("Interrupted while executing rule", "INTERRUPTED", ie);
        } catch (ExecutionException ee) {
            // Unwrap cause to surface original EVAL_FAILED / RUNTIME_ERROR signal.
            Throwable cause = ee.getCause();
            if (cause instanceof RuleEvalException ree) {
                throw ree;
            }
            if (cause instanceof RuleRuntimeException rre) {
                throw rre;
            }
            if (cause instanceof RuntimeException re) {
                throw new RuleRuntimeException(cause.getMessage(), "RUNTIME_ERROR", re);
            }
            throw new RuleRuntimeException(cause != null ? cause.getMessage() : "unknown", "RUNTIME_ERROR", ee);
        }
    }
}
