package com.orule.rule.execution.execution.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 §3.6 / §14.1 + TASK-2.1.1 / TASK-2.1.3 acceptance tests for
 * {@link AsyncThreadPoolConfig}.
 *
 * <p>The {@code rejectedExecutionHandler} property is package-private on
 * Spring's ThreadPoolTaskExecutor in 6.x, so we exercise it via
 * {@link AsyncThreadPoolConfig#resolveRejectionPolicy(String)} rather than
 * reflection.
 */
@DisplayName("AsyncThreadPoolConfig")
class AsyncThreadPoolConfigTest {

    private final AsyncThreadPoolConfig config = new AsyncThreadPoolConfig();

    @Test
    @DisplayName("default pool params: core=20/max=50/queue=500/prefix=rule-set-/policy=CALLER_RUNS")
    void defaults() {
        Object executor = config.ruleSetExecutor(20, 50, 500, "rule-set-", "CALLER_RUNS");
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor tpe = (ThreadPoolTaskExecutor) executor;
        assertThat(tpe.getCorePoolSize()).isEqualTo(20);
        assertThat(tpe.getMaxPoolSize()).isEqualTo(50);
        assertThat(tpe.getQueueCapacity()).isEqualTo(500);
        assertThat(tpe.getThreadNamePrefix()).isEqualTo("rule-set-");
    }

    @Test
    @DisplayName("policy: ABORT → ThreadPoolExecutor.AbortPolicy")
    void abort() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("ABORT");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    @DisplayName("policy: DISCARD → ThreadPoolExecutor.DiscardPolicy")
    void discard() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("DISCARD");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
    }

    @Test
    @DisplayName("policy: DISCARD_OLDEST → ThreadPoolExecutor.DiscardOldestPolicy")
    void discardOldest() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("DISCARD_OLDEST");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
    }

    @Test
    @DisplayName("policy: CALLER_RUNS → CallerRunsPolicy")
    void callerRuns() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("CALLER_RUNS");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("policy: unknown name falls back to CallerRunsPolicy")
    void unknownFallsBack() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("WEIRD");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("policy: null name → CallerRunsPolicy")
    void nullPolicy() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy(null);
        assertThat(h).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("policy: lowercase caller_runs (case-insensitive)")
    void caseInsensitive() {
        RejectedExecutionHandler h = AsyncThreadPoolConfig.resolveRejectionPolicy("caller_runs");
        assertThat(h).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
