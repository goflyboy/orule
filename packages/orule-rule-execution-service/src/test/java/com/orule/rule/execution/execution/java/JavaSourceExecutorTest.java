package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.error.RuleRuntimeException;
import com.orule.rule.execution.execution.ExecutorRegistry;
import com.orule.rule.execution.execution.ExecutorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0040 §3.8 + TASK-1.2.2 + TASK-1.2.5 acceptance tests for {@link JavaSourceExecutor}.
 *
 * <p>Tests focus on observable contract:
 * <ul>
 *   <li>Groovy executes the rule body in a Groovy sandbox shell</li>
 *   <li>Input context vars are visible inside the script</li>
 *   <li>Mutations in script become visible in output context</li>
 *   <li>Compile errors raise {@link RuleEvalException}</li>
 *   <li>Runtime errors raise {@link RuleRuntimeException}</li>
 *   <li>SPI registry resolves java-source → JavaSourceExecutor</li>
 * </ul>
 */
@DisplayName("JavaSourceExecutor")
class JavaSourceExecutorTest {

    JavaSourceExecutor executor;
    ExecutorRegistry registry;

    Map<String, Object> ctx;

    @BeforeEach
    void setUp() {
        GroovyClassCache cache = new GroovyClassCache(100L, 10L);
        executor = new JavaSourceExecutor(cache);
        registry = new ExecutorRegistry();
        ctx = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("executorType returns java-source")
    void executorTypeId() {
        assertThat(executor.executorType()).isEqualTo(ExecutorType.JAVA_SOURCE.typeId());
    }

    @Test
    @DisplayName("runs Groovy arithmetic with bound context")
    void runsArithmetic() {
        Map<String, Object> inputCtx = Map.of("a", 2, "b", 3);
        ExecutionOutput out = executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                // Groovy 3: `def` declares a local variable not visible in the Binding.
                // Scripts must explicitly assign to a name to be reflected in output.
                "result = a + b",
                inputCtx,
                new ExecutionMetadata("trace-1", "user-1", "tenant-1")));

        assertThat(out.success()).isTrue();
        assertThat(out.errorCode()).isNull();
        assertThat(out.context()).containsEntry("a", 2).containsEntry("b", 3);
        assertThat(((Number) out.context().get("result")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("preserves mutations to context variables (Q2 semantics)")
    void preservesMutations() {
        Map<String, Object> inputCtx = new LinkedHashMap<>();
        inputCtx.put("counter", 0);

        ExecutionOutput out = executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                "counter = counter + 1",
                inputCtx,
                new ExecutionMetadata("trace-2", "user-2", "tenant-2")));

        assertThat(out.success()).isTrue();
        assertThat(out.context()).containsEntry("counter", 1);
    }

    @Test
    @DisplayName("compile error => RuleEvalException (EVAL_FAILED)")
    void compileErrorMapsToRuleEval() {
        assertThatThrownBy(() -> executor.execute(new ExecutionInput(
                "java-source",
                "this is invalid groovy !!!",
                Map.of(),
                null)))
                .isInstanceOf(RuleEvalException.class)
                .hasMessageContaining("compile");
    }

    @Test
    @DisplayName("runtime exception => RuleRuntimeException (RUNTIME_ERROR)")
    void runtimeErrorMapsToRuleRuntime() {
        assertThatThrownBy(() -> executor.execute(new ExecutionInput(
                "java-source",
                "throw new RuntimeException(\"boom\")",
                Map.of(),
                null)))
                .isInstanceOf(RuleRuntimeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("empty sourceCode => success=false EVAL_FAILED with no exception")
    void emptySource() {
        ExecutionOutput out = executor.execute(new ExecutionInput(
                "java-source", "  ", Map.of(), null));
        assertThat(out.success()).isFalse();
        assertThat(out.errorCode()).isEqualTo("EVAL_FAILED");
    }

    @Test
    @DisplayName("ExecutorRegistry SPI resolves java-source → JavaSourceExecutor (TASK-1.2.1 + 1.2.2 wiring)")
    void spiResolvesThisExecutor() {
        assertThat(registry.registeredRuleExecutorTypes())
                .as("JavaSourceExecutor should be auto-registered via META-INF/services SPI")
                .contains(ExecutorType.JAVA_SOURCE.typeId());
    }
}
