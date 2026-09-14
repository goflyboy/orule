package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;

/**
 * Test fixture for {@link RuleExecutorService}.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link LoopbackExecutor} — passes input.context through</li>
 *   <li>{@link SlowExecutor} — sleeps {@code sleepMs} to trigger timeout enforcement</li>
 *   <li>{@link SpyExecutor} — records invocations for assertion</li>
 * </ul>
 *
 * <p>Test-only helpers; placed in {@code test/java} so they are not packaged into the
 * production artifact.
 */
public final class ExecutorTestSupport {
    private ExecutorTestSupport() {}

    public static final class LoopbackExecutor implements RuleExecutor {
        @Override public String executorType() { return "loopback"; }
        @Override public ExecutionOutput execute(ExecutionInput input) {
            return new ExecutionOutput(input.context(), true, null, null);
        }
    }

    public static final class SlowExecutor implements RuleExecutor {
        private final long sleepMs;
        public SlowExecutor(long sleepMs) { this.sleepMs = sleepMs; }
        @Override public String executorType() { return "slow"; }
        @Override public ExecutionOutput execute(ExecutionInput input) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return new ExecutionOutput(input.context(), true, null, null);
        }
    }

    public static final class SpyExecutor implements RuleExecutor {
        public int invocationCount = 0;
        @Override public String executorType() { return "spy"; }
        @Override public ExecutionOutput execute(ExecutionInput input) {
            invocationCount++;
            return new ExecutionOutput(input.context(), true, null, null);
        }
    }
}
