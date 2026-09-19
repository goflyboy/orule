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

    @Test
    @DisplayName("RFC-0043 + RFC-0045: prefix + Customer vip = map compares enum identity")
    void typedLocal_fromMapComparesEnum() {
        Map<String, Object> alice = new LinkedHashMap<>();
        alice.put("name", "alice");
        alice.put("tier", "VIP");
        Map<String, Object> byId = new LinkedHashMap<>();
        byId.put("alice", alice);
        Map<String, Object> inputCtx = new LinkedHashMap<>();
        inputCtx.put("customersById", byId);

        ExecutionOutput out = executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                """
                Customer vip = customersById["alice"]
                matched = (vip.tier == CustomerTier.VIP)
                """,
                inputCtx,
                new ExecutionMetadata("trace-43-1", "user-1", "tenant-1"),
                com.orule.rule.execution.execution.java.metadata.ResolvedObjectTestFixtures.customerOrderTier()));

        assertThat(out.success()).isTrue();
        assertThat(out.context()).containsEntry("matched", true);
    }

    @Test
    @DisplayName("RFC-0043: typed local mutation does not write through without assign-back")
    void typedLocal_copiesWithoutWriteThrough() {
        Map<String, Object> alice = new LinkedHashMap<>();
        alice.put("name", "alice");
        alice.put("tier", "VIP");
        alice.put("tagged", false);
        Map<String, Object> byId = new LinkedHashMap<>();
        byId.put("alice", alice);
        Map<String, Object> inputCtx = new LinkedHashMap<>();
        inputCtx.put("customersById", byId);

        ExecutionOutput out = executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                """
                Customer vip = customersById["alice"]
                vip.tagged = true
                """,
                inputCtx,
                new ExecutionMetadata("trace-43-2", "user-1", "tenant-1"),
                com.orule.rule.execution.execution.java.metadata.ResolvedObjectTestFixtures.customerOrderTier()));

        assertThat(out.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> outById = (Map<String, Object>) out.context().get("customersById");
        @SuppressWarnings("unchecked")
        Map<String, Object> outAlice = (Map<String, Object>) outById.get("alice");
        assertThat(outAlice.get("tagged")).isEqualTo(false);
    }

    @Test
    @DisplayName("RFC-0043 + RFC-0045: hydrate customer.tier == CustomerTier.VIP without typed local")
    void hydrate_topLevelCustomerComparesEnum() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "alice");
        customer.put("tier", "VIP");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("totalAmount", 250);
        order.put("discount", 0);
        Map<String, Object> inputCtx = new LinkedHashMap<>();
        inputCtx.put("customer", customer);
        inputCtx.put("order", order);

        ExecutionOutput out = executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                """
                if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
                """,
                inputCtx,
                new ExecutionMetadata("trace-43-3", "user-1", "tenant-1"),
                com.orule.rule.execution.execution.java.metadata.ResolvedObjectTestFixtures.customerOrderTier()));

        assertThat(out.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> outOrder = (Map<String, Object>) out.context().get("order");
        assertThat(((Number) outOrder.get("discount")).intValue()).isEqualTo(30);
    }

    @Test
    @DisplayName("RFC-0043: closure source fails compilation")
    void closureRejected() {
        assertThatThrownBy(() -> executor.execute(new ExecutionInput(
                ExecutorType.JAVA_SOURCE.typeId(),
                "result = [1,2,3].any { it > 1 }",
                Map.of(),
                null)))
                .isInstanceOf(RuleEvalException.class);
    }
}
