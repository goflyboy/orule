package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.api.dto.ExecutionMetadata;
import com.orule.rule.execution.api.dto.ExecutionOutput;
import com.orule.rule.execution.error.RuleEvalException;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC-0045 §4.5 unit tests for the generalized {@link ContextHydrator}.
 *
 * <p>Uses the JavaSourceExecutor directly so we exercise the integration of
 * hydrate + dehydrate + Groovy prefix generation. No Feign / Spring context.
 */
@DisplayName("ContextHydrator generalization")
class ContextHydratorGeneralizationTest {

    JavaSourceExecutor executor;

    @BeforeEach
    void setUp() {
        GroovyClassCache cache = new GroovyClassCache(100L, 10L);
        executor = new JavaSourceExecutor(cache);
    }

    private static List<ResolvedObjectType> fixture() {
        ResolvedObjectType tier = ResolvedObjectType.enumOf("CustomerTier", List.of(
                new ResolvedObjectType.EnumValue("VIP",  "VIP", 1),
                new ResolvedObjectType.EnumValue("GOLD", "Gold", 2)));
        ResolvedObjectType customer = ResolvedObjectType.classOf("customer", "Customer", List.of(
                ResolvedObjectType.attr("name", "primitive", "string", null),
                ResolvedObjectType.attr("tier", "object",    "CustomerTier", null),
                ResolvedObjectType.attr("tagged", "primitive", "boolean", null)));
        ResolvedObjectType order = ResolvedObjectType.classOf("order", "Order", List.of(
                ResolvedObjectType.attr("totalAmount", "primitive", "number", null),
                ResolvedObjectType.attr("discount",    "primitive", "number", null)));
        return List.of(tier, customer, order);
    }

    @Test
    @DisplayName("hydrated top-level slot enables direct enum comparison without typed local")
    void hydrateEnablesEnumCompare() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "alice");
        customer.put("tier", "VIP");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("totalAmount", 250);
        order.put("discount", 0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customer", customer);
        input.put("order", order);

        ExecutionOutput out = executor.execute(new ExecutionInput(
                "java-source",
                """
                if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
                """,
                input,
                new ExecutionMetadata("t", "u", "tn"),
                fixture()));

        assertThat(out.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> outOrder = (Map<String, Object>) out.context().get("order");
        assertThat(((Number) outOrder.get("discount")).intValue()).isEqualTo(30);
    }

    @Test
    @DisplayName("unknown slot keys are preserved verbatim (no hydrate)")
    void unknownSlotPreserved() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("anything", 42);
        ExecutionOutput out = executor.execute(new ExecutionInput(
                "java-source", "result = anything", input,
                new ExecutionMetadata("t", "u", "tn"), fixture()));
        assertThat(out.success()).isTrue();
        assertThat(out.context().get("anything")).isEqualTo(42);
    }

    @Test
    @DisplayName("empty resolvedObjectTypes keeps RFC-0043 verbatim compatibility path")
    void emptyResolvedKeepsVerbatim() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", 2);
        input.put("b", 3);
        ExecutionOutput out = executor.execute(new ExecutionInput(
                "java-source", "result = a + b", input,
                new ExecutionMetadata("t", "u", "tn"), List.of()));
        assertThat(out.success()).isTrue();
        assertThat(((Number) out.context().get("result")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("closure source fails compilation even with metadata prefix")
    void closureStillRejected() {
        assertThatThrownBy(() -> executor.execute(new ExecutionInput(
                "java-source",
                "result = [1,2,3].any { it > 1 }",
                Map.of(),
                null,
                fixture())))
                .isInstanceOf(RuleEvalException.class);
    }
}
