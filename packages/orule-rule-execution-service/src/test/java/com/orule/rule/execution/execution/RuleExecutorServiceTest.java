package com.orule.rule.execution.execution;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.ExecutorNotRegisteredException;
import com.orule.rule.execution.error.RuleRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0040 §3.13 + TASK-1.2.5 acceptance tests for {@link RuleExecutorService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>happy-path execution through SPI registered executor</li>
 *   <li>per-call timeout surfaces RuleRuntimeException with TIMEOUT errorCode</li>
 *   <li>unknown executorType surfaces ExecutorNotRegisteredException</li>
 *   <li>timeout cancellation does not block the calling thread</li>
 * </ul>
 */
@DisplayName("RuleExecutorService (timeout + routing)")
class RuleExecutorServiceTest {

    private ExecutorRegistry registry;
    private RuleExecutorService service;

    @BeforeEach
    void setUp() {
        registry = new ExecutorRegistry();
        // Minimal pool + 200ms default timeout to keep the suite fast.
        service = new RuleExecutorService(registry, 2, 4, 8, 200L);
    }

    @Test
    @DisplayName("passes through execution for known executor type")
    void passesThrough() {
        AtomicReference<ExecutionInput> seen = new AtomicReference<>();
        registry.registerForTesting(new RuleExecutor() {
            @Override public String executorType() { return "loopback"; }
            @Override public ExecutionOutput execute(ExecutionInput input) {
                seen.set(input);
                return new ExecutionOutput(input.context(), true, null, null);
            }
        });

        ExecutionOutput out = service.execute("loopback",
                new ExecutionInput("loopback", "ignored",
                        Map.of("k", "v"),
                        null));

        assertThat(out.success()).isTrue();
        assertThat(out.context()).containsEntry("k", "v");
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().sourceCode()).isEqualTo("ignored");
    }

    @Test
    @DisplayName("throws ExecutorNotRegisteredException for unknown type")
    void unknownExecutorType() {
        assertThatThrownBy(() -> service.execute("nope",
                new ExecutionInput("nope", "src", Map.of(), null)))
                .isInstanceOf(ExecutorNotRegisteredException.class);
    }

    @Test
    @DisplayName("enforces timeout: slow executor → RuleRuntimeException with TIMEOUT errorCode")
    void enforcesTimeout() {
        registry.registerForTesting(new RuleExecutor() {
            @Override public String executorType() { return "slow"; }
            @Override public ExecutionOutput execute(ExecutionInput input) {
                try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return new ExecutionOutput(input.context(), true, null, null);
            }
        });

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(() -> service.execute("slow",
                new ExecutionInput("slow", "src", Map.of(), null),
                /* timeoutMs */ 100L))
                .isInstanceOf(RuleRuntimeException.class)
                .extracting(e -> ((RuleRuntimeException) e).getErrorCode())
                .isEqualTo("TIMEOUT");
        long elapsed = System.currentTimeMillis() - startedAt;

        // The test is cancelled well within the timeout window — the calling thread must
        // never block for the full sleep.
        assertThat(elapsed).isLessThan(1000L);
    }

    @Test
    @DisplayName("rejects null executorType with IllegalArgumentException")
    void rejectsNullType() {
        assertThatThrownBy(() -> service.execute(null,
                new ExecutionInput("x", "src", Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
