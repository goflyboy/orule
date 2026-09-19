package com.orule.rule.execution.systemtest;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.systemtest.support.RuleExecutionResult;
import com.orule.rule.execution.systemtest.support.RuleExecutionSystemTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.orule.rule.execution.systemtest.support.RuleExecutionResult.is;
import static org.hamcrest.Matchers.equalTo;

/**
 * RFC-0045 §4.7 system test: full end-to-end run via
 * {@code RuleExecutionApplicationService.execute(...)} with the Groovy prefix
 * derived from {@code RuleMetadataResponseV2.objectTypeCodes} + secondary
 * Feign fetches for each {@link ResolvedObjectType}.
 *
 * <p>Mirrors {@code ComplexServiceFrameworkedSystemTest} but exercises the
 * full Spring MVC pipeline (POST /api/v1/rule-executions) rather than the
 * bare executor. Catches any wiring drift between MockRuleBuilder, the
 * controller, the application service, and the executor.
 */
@DisplayName("Metadata-driven prefix system test (RFC-0045)")
public class MetadataDrivenPrefixSystemTest extends RuleExecutionSystemTestBase {

    private static final String DOMAIN = "ORDER";

    private static List<ResolvedObjectType> fixture() {
        return List.of(
                ResolvedObjectType.enumOf("CustomerTier", List.of(
                        new ResolvedObjectType.EnumValue("VIP",  "VIP",  1),
                        new ResolvedObjectType.EnumValue("GOLD", "Gold", 2))),
                ResolvedObjectType.classOf("customer", "Customer", List.of(
                        ResolvedObjectType.attr("name", "primitive", "string", null),
                        ResolvedObjectType.attr("tier", "object",    "CustomerTier", null))),
                ResolvedObjectType.classOf("order", "Order", List.of(
                        ResolvedObjectType.attr("totalAmount", "primitive", "number", null),
                        ResolvedObjectType.attr("discount",    "primitive", "number", null))));
    }

    private static Map<String, Object> customer(String name, String tier) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("tier", tier);
        return m;
    }

    @Test
    @DisplayName("A0 nested object typed-local: metadata-driven prefix builds enum + classes")
    void a0_typedLocal() {
        mockRule("ORDER_VIP_DISCOUNT_A0")
                .withDomainCode(DOMAIN)
                .withResolvedObjectTypes(fixture())
                .withSource("""
                Customer c = customer
                if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_VIP_DISCOUNT_A0",
                input().put("customer", customer("alice", "VIP"))
                       .put("order", Map.of("totalAmount", 250, "discount", 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .field("/outputContext/order/totalAmount", is(250))
         .fieldString("/outputContext/customer/tier", equalTo("VIP"));
    }

    @Test
    @DisplayName("B list of customers: metadata-driven prefix supports for + get")
    void b_listCustomers() {
        mockRule("ORDER_LIST_VIP_DISCOUNT")
                .withDomainCode(DOMAIN)
                .withResolvedObjectTypes(fixture())
                .withSource("""
                for (int i = 0; i < customers.size(); i = i + 1) {
                    Customer c = customers.get(i)
                    if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                        order.discount = 30
                    }
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_LIST_VIP_DISCOUNT",
                input().put("customers", List.of(customer("alice", "VIP"), customer("bob", "GOLD")))
                       .put("order", Map.of("totalAmount", 250, "discount", 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .fieldString("/outputContext/customers/0/name", equalTo("alice"));
    }

    @Test
    @DisplayName("Resolve without resolvedObjectTypes: rule body executes verbatim (RFC-0043 fallback)")
    void c_emptyResolvedFallback() {
        mockRule("ORDER_FALLBACK")
                .withDomainCode(DOMAIN)
                .withObjectTypeCodes(List.of())
                .withSource("result = a + b");
        RuleExecutionResult r = executeRule("ORDER_FALLBACK",
                input().put("a", 2).put("b", 3));
        r.isOk()
         .field("/outputContext/result", is(5));
    }
}
