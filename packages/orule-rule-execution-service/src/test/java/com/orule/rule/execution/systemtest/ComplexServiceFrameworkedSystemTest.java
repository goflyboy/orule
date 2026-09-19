package com.orule.rule.execution.systemtest;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.systemtest.support.RuleExecutionResult;
import com.orule.rule.execution.systemtest.support.RuleExecutionSystemTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.orule.rule.execution.systemtest.support.RuleExecutionResult.containsString;
import static com.orule.rule.execution.systemtest.support.RuleExecutionResult.is;
import static org.hamcrest.Matchers.equalTo;

/**
 * RFC-0043 nested object / List / Map system test, upgraded to RFC-0045
 * metadata-driven prefix (no longer uses {@code DomainTypePrefix.CUSTOMER_ORDER}).
 *
 * <p>The Groovy source no longer embeds the class/enum prefix; the executor
 * assembles it from {@link ResolvedObjectType} fixtures attached via
 * {@code MockRuleBuilder.withResolvedObjectTypes(...)}.
 */
@DisplayName("ComplexService ? nested object/List/Map (RFC-0043 + RFC-0045)")
public class ComplexServiceFrameworkedSystemTest extends RuleExecutionSystemTestBase {

    private static final String DOMAIN = "ORDER";

    private static List<ResolvedObjectType> fixture() {
        ResolvedObjectType tier = ResolvedObjectType.enumOf("CustomerTier", List.of(
                new ResolvedObjectType.EnumValue("VIP",    "VIP customer",    1),
                new ResolvedObjectType.EnumValue("GOLD",   "Gold card",       2),
                new ResolvedObjectType.EnumValue("SILVER", "Silver card",     3),
                new ResolvedObjectType.EnumValue("BRONZE", "Bronze card",     4)));
        ResolvedObjectType customer = ResolvedObjectType.classOf("customer", "Customer", List.of(
                ResolvedObjectType.attr("name",   "primitive", "string", null),
                ResolvedObjectType.attr("tier",   "object",    "CustomerTier", null),
                ResolvedObjectType.attr("tagged", "primitive", "boolean", null)));
        ResolvedObjectType order = ResolvedObjectType.classOf("order", "Order", List.of(
                ResolvedObjectType.attr("totalAmount", "primitive", "number", null),
                ResolvedObjectType.attr("discount",    "primitive", "number", null)));
        return List.of(tier, customer, order);
    }

    @Test
    @DisplayName("A0 nested object: typed local then write order.discount (metadata-driven prefix)")
    void nested_object_typedLocal() {
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
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .field("/outputContext/order/totalAmount", is(250))
         .fieldString("/outputContext/customer/tier", equalTo("VIP"));
    }

    @Test
    @DisplayName("B list of objects: for + get, no closure")
    void list_customers_forGet() {
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
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .fieldString("/outputContext/customers/0/name", equalTo("alice"));
    }

    @Test
    @DisplayName("C closure / any{} is rejected")
    void closure_any_rejected() {
        mockRule("ORDER_ANY_FORBIDDEN")
                .withDomainCode(DOMAIN)
                .withResolvedObjectTypes(fixture())
                .withSource("""
                if (customers.any { it.tier == CustomerTier.VIP }) {
                    order.discount = 30
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_ANY_FORBIDDEN",
                input().put("customers", List.of(customer("alice", "VIP")))
                       .put("order", new Order(250, 0)));
        r.isFailed("EVAL_FAILED").fieldMessage(containsString("Closure"));
    }

    // -- helpers (kept local; not a public SDK) --

    private static Map<String, Object> customer(String name, String tier) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("tier", tier);
        m.put("tagged", Boolean.FALSE);
        return m;
    }

    public static class Order {
        private int totalAmount;
        private int discount;
        public Order() {}
        public Order(int totalAmount, int discount) {
            this.totalAmount = totalAmount;
            this.discount = discount;
        }
        public int getTotalAmount() { return totalAmount; }
        public int getDiscount() { return discount; }
    }
}
