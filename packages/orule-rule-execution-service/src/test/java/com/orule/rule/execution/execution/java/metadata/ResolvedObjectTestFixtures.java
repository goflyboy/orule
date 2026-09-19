package com.orule.rule.execution.execution.java.metadata;

import java.util.List;

/**
 * Shared test fixtures for RFC-0043 / RFC-0045 customer/order/tier scenarios.
 */
public final class ResolvedObjectTestFixtures {

    private ResolvedObjectTestFixtures() {}

    public static List<ResolvedObjectType> customerOrderTier() {
        ResolvedObjectType tier = ResolvedObjectType.enumOf("CustomerTier", List.of(
                new ResolvedObjectType.EnumValue("VIP",  "VIP", 1),
                new ResolvedObjectType.EnumValue("GOLD", "Gold", 2)));
        ResolvedObjectType customer = ResolvedObjectType.classOf("customer", "Customer", List.of(
                ResolvedObjectType.attr("name",   "primitive", "string", null),
                ResolvedObjectType.attr("tier",   "object",    "CustomerTier", null),
                ResolvedObjectType.attr("tagged", "primitive", "boolean", null)));
        ResolvedObjectType order = ResolvedObjectType.classOf("order", "Order", List.of(
                ResolvedObjectType.attr("totalAmount", "primitive", "number", null),
                ResolvedObjectType.attr("discount",    "primitive", "number", null)));
        return List.of(tier, customer, order);
    }
}
