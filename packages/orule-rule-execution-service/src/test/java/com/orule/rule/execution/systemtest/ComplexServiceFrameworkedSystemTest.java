package com.orule.rule.execution.systemtest;

import com.orule.rule.execution.execution.java.DomainTypePrefix;
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
 * RFC-0043 nested object / List / Map system test.
 *
 * <p>Independent from {@link CustomerServiceFrameworkedSystemTest} (RFC-0042
 * flattened demo). Groovy source may still use {@code int}/{@code ArrayList};
 * SimpleTS target form is RFC-0043 ?4.6.
 */
@DisplayName("ComplexService ? nested object/List/Map (RFC-0043)")
public class ComplexServiceFrameworkedSystemTest extends RuleExecutionSystemTestBase {

    @Test
    @DisplayName("A0 nested object: typed local then write order.discount")
    void nested_object_typedLocal() {
        mockRule("ORDER_VIP_DISCOUNT_A0").withSource(prefix() + """
                Customer c = customer
                if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_VIP_DISCOUNT_A0",
                input().put("customer", new Customer("alice", CustomerTier.VIP))
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .field("/outputContext/order/totalAmount", is(250))
         .fieldString("/outputContext/customer/tier", equalTo("VIP"));
    }

    @Test
    @DisplayName("A1 nested object: hydrated customer.tier == CustomerTier.VIP")
    void nested_object_readAndWrite() {
        mockRule("ORDER_VIP_DISCOUNT").withSource(prefix() + """
                if (customer.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 30
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_VIP_DISCOUNT",
                input().put("customer", new Customer("alice", CustomerTier.VIP))
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .field("/outputContext/order/totalAmount", is(250))
         .fieldString("/outputContext/customer/tier", equalTo("VIP"));
    }

    @Test
    @DisplayName("B list of objects: for + get, no closure")
    void list_customers_forGet() {
        mockRule("ORDER_LIST_VIP_DISCOUNT").withSource(prefix() + """
                for (int i = 0; i < customers.size(); i = i + 1) {
                    Customer c = customers.get(i)
                    if (c.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                        order.discount = 30
                    }
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_LIST_VIP_DISCOUNT",
                input().put("customers", List.of(
                                new Customer("alice", CustomerTier.VIP),
                                new Customer("bob", CustomerTier.GOLD)))
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(30))
         .fieldString("/outputContext/customers/0/name", equalTo("alice"));
    }

    @Test
    @DisplayName("C map of objects: typed local + keySet for")
    void map_customersById_typedLocalAndTraverse() {
        mockRule("ORDER_MAP_VIP_DISCOUNT").withSource(prefix() + """
                Customer vip = customersById["alice"]
                if (vip.tier == CustomerTier.VIP && order.totalAmount >= 200) {
                    order.discount = 20
                    vip.tagged = true
                    customersById["alice"] = vip
                }
                def keys = new ArrayList(customersById.keySet())
                for (int i = 0; i < keys.size(); i = i + 1) {
                    String k = keys.get(i)
                    Customer c = customersById[k]
                    if (c.tier == CustomerTier.GOLD) {
                        c.tagged = false
                        customersById[k] = c
                    }
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_MAP_VIP_DISCOUNT",
                input().put("customersById", Map.of(
                                "alice", new Customer("alice", CustomerTier.VIP),
                                "bob", new Customer("bob", CustomerTier.GOLD)))
                       .put("order", new Order(250, 0)));
        r.isOk()
         .field("/outputContext/order/discount", is(20))
         .field("/outputContext/customersById/alice/tagged", is(true))
         .field("/outputContext/customersById/bob/tagged", is(false));
    }

    @Test
    @DisplayName("C' closure / any{} is rejected")
    void closure_any_rejected() {
        mockRule("ORDER_ANY_FORBIDDEN").withSource(prefix() + """
                if (customers.any { it.tier == CustomerTier.VIP }) {
                    order.discount = 30
                }
                """);
        RuleExecutionResult r = executeRule("ORDER_ANY_FORBIDDEN",
                input().put("customers", List.of(new Customer("alice", CustomerTier.VIP)))
                       .put("order", new Order(250, 0)));
        r.isFailed("EVAL_FAILED").fieldMessage(containsString("Closure"));
    }

    @Test
    @DisplayName("typed local without assign-back does not write through")
    void typedLocal_withoutAssignBack_doesNotWriteThrough() {
        mockRule("ORDER_NO_ASSIGN_BACK").withSource(prefix() + """
                Customer vip = customersById["alice"]
                vip.tagged = true
                """);
        RuleExecutionResult r = executeRule("ORDER_NO_ASSIGN_BACK",
                input().put("customersById", Map.of(
                        "alice", new Customer("alice", CustomerTier.VIP, false))));
        r.isOk()
         .field("/outputContext/customersById/alice/tagged", is(false));
    }

    private static String prefix() {
        return DomainTypePrefix.CUSTOMER_ORDER;
    }

    public enum CustomerTier { VIP, GOLD, SILVER, BRONZE }

    public static class Customer {
        private String name;
        private CustomerTier tier;
        private Boolean tagged;

        public Customer() {}

        public Customer(String name, CustomerTier tier) {
            this.name = name;
            this.tier = tier;
        }

        public Customer(String name, CustomerTier tier, Boolean tagged) {
            this.name = name;
            this.tier = tier;
            this.tagged = tagged;
        }

        public String getName() { return name; }
        public CustomerTier getTier() { return tier; }
        public Boolean getTagged() { return tagged; }
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
