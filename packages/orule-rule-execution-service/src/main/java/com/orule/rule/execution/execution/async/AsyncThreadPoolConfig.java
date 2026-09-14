package com.orule.rule.execution.execution.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async pool configuration for rule-set dispatch (RFC-0040 §3.6 / TASK-2.1.1).
 *
 * <p>Named {@code ruleSetExecutor}, the bean is referenced by
 * {@link org.springframework.scheduling.annotation.Async @Async("ruleSetExecutor")}.
 *
 * <p>Defaults (RFC §3.6):
 * <ul>
 *   <li>corePoolSize=20, maxPoolSize=50, queueCapacity=500</li>
 *   <li>CallerRunsPolicy so we degrade gracefully under saturation</li>
 * </ul>
 *
 * <p>Tunable via {@code orule.execution.async.*} keys in application.yml.
 */
@Configuration
public class AsyncThreadPoolConfig {

    @Bean(name = "ruleSetExecutor")
    public Executor ruleSetExecutor(
            @org.springframework.beans.factory.annotation.Value("${orule.execution.async.core-size:20}") int coreSize,
            @org.springframework.beans.factory.annotation.Value("${orule.execution.async.max-size:50}") int maxSize,
            @org.springframework.beans.factory.annotation.Value("${orule.execution.async.queue-capacity:500}") int queueCapacity,
            @org.springframework.beans.factory.annotation.Value("${orule.execution.async.thread-name-prefix:rule-set-}") String namePrefix) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(coreSize);
        ex.setMaxPoolSize(maxSize);
        ex.setQueueCapacity(queueCapacity);
        ex.setThreadNamePrefix(namePrefix);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
