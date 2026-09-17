package com.orule.rule.execution.execution.java;

/**
 * Groovy source prefix for the Customer/Order demo domain (RFC-0043 ?4.2).
 *
 * <p>v0.2 injects this text ahead of the rule body so {@code Customer vip = ...}
 * and {@code CustomerTier.VIP} compile inside the Groovy sandbox. This is not a
 * generic ObjectType code generator.
 */
public final class DomainTypePrefix {

    /**
     * Script-level enum/class definitions isomorphic with
     * {@code ComplexServiceFrameworkedSystemTest} POJOs.
     */
    public static final String CUSTOMER_ORDER = """
            enum CustomerTier { VIP, GOLD, SILVER, BRONZE }

            class Customer {
                String name
                CustomerTier tier
                Boolean tagged
            }

            class Order {
                Integer totalAmount
                Integer discount
            }

            """;

    private DomainTypePrefix() {}

    /**
     * Prepend the Customer/Order prefix unless the source already defines it.
     * Tests may inline the same prefix to lock the execution contract; the
     * executor must not emit duplicate class declarations.
     */
    public static String apply(String ruleBody) {
        if (ruleBody == null) {
            return CUSTOMER_ORDER;
        }
        String body = ruleBody.stripLeading();
        if (body.contains("enum CustomerTier") && body.contains("class Customer")
                && body.contains("class Order")) {
            return body;
        }
        return CUSTOMER_ORDER + body;
    }
}
