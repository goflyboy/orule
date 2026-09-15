package com.orule.rule.execution.systemtest;

import com.orule.rule.execution.systemtest.support.RuleExecutionResult;
import com.orule.rule.execution.systemtest.support.RuleExecutionSystemTestBase;
import com.orule.rule.execution.systemtest.support.RuleSetExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static com.orule.rule.execution.systemtest.support.RuleExecutionResult.containsString;
import static com.orule.rule.execution.systemtest.support.RuleExecutionResult.is;
import static org.hamcrest.Matchers.equalTo;

/**
 * RFC-0042 §4.2 demo system test.
 *
 * <p>Companion to {@link CustomerServiceSystemTest} (RFC-0041 v0.1, commit
 * {@code a1997d1}). Each {@code @Test} method body stays ≤10 effective lines
 * (excluding blanks, comments, and braces) and exercises the same scenarios
 * as RFC-0041 v0.1 with a one-call-per-step surface.
 *
 * <p>Side-by-side comparison (effective lines per test):
 * <table>
 *   <tr><th>Scenario</th><th>RFC-0041 v0.1</th><th>RFC-0042 v0.1</th></tr>
 *   <tr><td>sync happy</td><td>~60</td><td>4</td></tr>
 *   <tr><td>sync eval-fail</td><td>~30</td><td>3</td></tr>
 *   <tr><td>sync runtime-error</td><td>~25</td><td>4</td></tr>
 *   <tr><td>sync missing-tenant</td><td>~12</td><td>3</td></tr>
 *   <tr><td>ruleSet happy</td><td>~30</td><td>5</td></tr>
 *   <tr><td>ruleSet failed</td><td>~25</td><td>4</td></tr>
 *   <tr><td>POJO binding (new)</td><td>—</td><td>9</td></tr>
 * </table>
 */
@DisplayName("CustomerService → rule-execution-service (frameworked, RFC-0042)")
public class CustomerServiceFrameworkedSystemTest extends RuleExecutionSystemTestBase {

    @Test
    @DisplayName("sync happy")
    void sync_happy() {
        mockRule("ORDER_VIP_DISCOUNT").withSource("result = price");
        RuleExecutionResult r = executeRule("ORDER_VIP_DISCOUNT",
                input().set("price", 100));
        r.isOk().field("/outputContext/result", is(100));
    }

    @Test
    @DisplayName("sync eval-fail (Groovy compile error)")
    void sync_evalFail() {
        mockRule("BAD_RULE").withSource("this is invalid groovy !!!");
        RuleExecutionResult r = executeRule("BAD_RULE", input().set("x", 1));
        r.isFailed("EVAL_FAILED");
    }

    @Test
    @DisplayName("sync runtime-error")
    void sync_runtimeError() {
        mockRule("BOOM_RULE").withSource("throw new RuntimeException(\"boom\")");
        RuleExecutionResult r = executeRule("BOOM_RULE", input().set("x", 1));
        r.isFailed("RUNTIME_ERROR").fieldMessage(containsString("boom"));
    }

    @Test
    @DisplayName("sync missing tenant → 400 MISSING_TENANT_HEADER")
    void sync_missingTenant() {
        RuleExecutionResult r = executeRule("X", input(), withoutTenantHeader());
        r.isHttpStatus(HttpStatus.BAD_REQUEST)
         .fieldString("/errorCode", equalTo("MISSING_TENANT_HEADER"));
    }

    @Test
    @DisplayName("ruleSet async happy")
    void ruleSet_happy() {
        mockRule("ORDER_PROMOTION_SUITE")
                .withSource("discount = (int) (order_amount * 0.1)\nprocessed = true");
        RuleSetExecutionResult r = executeRuleSet("ORDER_PROMOTION_SUITE",
                input().set("order_amount", 1000))
                .awaitTerminal(Duration.ofSeconds(10));
        r.isOk()
         .field("/outputContext/discount", is(100))
         .field("/outputContext/processed", is(true));
    }

    @Test
    @DisplayName("ruleSet async rule-fail → FAILED")
    void ruleSet_fail() {
        mockRule("BAD_RULE_SET").withSource("this is invalid groovy !!!");
        RuleSetExecutionResult r = executeRuleSet("BAD_RULE_SET",
                input().set("x", 1))
                .awaitTerminal(Duration.ofSeconds(10));
        r.isFailed("RULE_SET_FAILED");
    }

    @Test
    @DisplayName("POJO binding: Customer fields flow into Groovy binding")
    void customer_pojoBinding() {
        mockRule("VIP_DISCOUNT").withSource(
                "total = price * quantity\n"
              + "vip_discount = (int) (vip ? total * 0.2 : 0)\n"
              + "final_amount = total - vip_discount");
        Customer alice = new Customer("alice", 30, true);
        RuleExecutionResult r = executeRule("VIP_DISCOUNT",
                input().set("price", 100).set("quantity", 2).also(alice));
        r.isOk()
         .field("/outputContext/total", is(200))
         .field("/outputContext/vip_discount", is(40))
         .field("/outputContext/final_amount", is(160));
    }

    // ===================================================================
    // Demo POJO (RFC-0042 §4.2)
    // ===================================================================

    /**
     * Demo POJO showing the framework's bean → context flattening (RFC-0042 §4.5).
     * Field names appear at the top level of the Groovy binding, so the rule
     * references {@code isVip} rather than {@code customer.isVip}.
     *
     * <p>Inline per user direction; not promoted to a shared fixture.
     */
    public static class Customer {
        private String name;
        private int age;
        private boolean vip;

        public Customer() {}

        public Customer(String name, int age, boolean vip) {
            this.name = name;
            this.age = age;
            this.vip = vip;
        }

        public String getName() { return name; }
        public int getAge() { return age; }
        public boolean isVip() { return vip; }

        public Customer withName(String name) { this.name = name; return this; }
        public Customer withAge(int age) { this.age = age; return this; }
        public Customer withVip(boolean vip) { this.vip = vip; return this; }
    }
}
