package com.orule.rule.execution.execution.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async pool configuration for rule-set dispatch (RFC-0040 §3.6 / §3.12,
 * TASK-2.1.1 / TASK-2.1.3).
 *
 * <p>Named {@code ruleSetExecutor}, the bean is referenced by
 * {@link org.springframework.scheduling.annotation.Async @Async("ruleSetExecutor")}.
 *
 * <p>Defaults (RFC §3.6 / §14.1):
 * <ul>
 *   <li>corePoolSize=20, maxPoolSize=50, queueCapacity=500</li>
 *   <li>rejectionPolicy=CALLER_RUNS (CallerRunsPolicy)</li>
 * </ul>
 *
 * <p>Tunable via {@code orule.execution.async.pool.*} keys in application.yml.
 */
@Configuration
public class AsyncThreadPoolConfig {

    @Bean(name = "ruleSetExecutor")
    public Executor ruleSetExecutor(
            @Value("${orule.execution.async.pool.core-size:20}") int coreSize,
            @Value("${orule.execution.async.pool.max-size:50}") int maxSize,
            @Value("${orule.execution.async.pool.queue-capacity:500}") int queueCapacity,
            @Value("${orule.execution.async.pool.thread-name-prefix:rule-set-}") String namePrefix,
            @Value("${orule.execution.async.pool.rejection-policy:CALLER_RUNS}") String rejectionPolicy) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(coreSize);
        ex.setMaxPoolSize(maxSize);
        ex.setQueueCapacity(queueCapacity);
        ex.setThreadNamePrefix(namePrefix);
        ex.setRejectedExecutionHandler(resolveRejectionPolicy(rejectionPolicy));
        ex.initialize();
        return ex;
    }

    /**
     * Resolve a string policy name into a {@link RejectedExecutionHandler}.
     * Accepts {@code CALLER_RUNS} (default), {@code ABORT}, {@code DISCARD},
     * {@code DISCARD_OLDEST}. Unknown names fall back to {@link ThreadPoolExecutor.CallerRunsPolicy}.
     */
    static RejectedExecutionHandler resolveRejectionPolicy(String name) {
        if (name == null) return new ThreadPoolExecutor.CallerRunsPolicy();
        switch (name.trim().toUpperCase()) {
            case "ABORT":
                return new ThreadPoolExecutor.AbortPolicy();
            case "DISCARD":
                return new ThreadPoolExecutor.DiscardPolicy();
            case "DISCARD_OLDEST":
                return new ThreadPoolExecutor.DiscardOldestPolicy();
            case "CALLER_RUNS":
            default:
                return new ThreadPoolExecutor.CallerRunsPolicy();
        }
    }
}
