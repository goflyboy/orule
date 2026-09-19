package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0045 §4.2 unit tests for {@link DomainTypePrefixGenerator}.
 */
@DisplayName("DomainTypePrefixGenerator")
class DomainTypePrefixGeneratorTest {

    @Test
    @DisplayName("render returns empty for null/empty input")
    void renderEmpty() {
        assertThat(DomainTypePrefixGenerator.render(null)).isEmpty();
        assertThat(DomainTypePrefixGenerator.render(List.of())).isEmpty();
    }

    @Test
    @DisplayName("render emits enums first, sorted by programCode")
    void renderEnumsFirst() {
        List<ResolvedObjectType> ots = List.of(
                ResolvedObjectType.enumOf("Zebra", List.of(new ResolvedObjectType.EnumValue("A", "A", 1))),
                ResolvedObjectType.enumOf("Alpha", List.of(new ResolvedObjectType.EnumValue("X", "X", 1))));
        String out = DomainTypePrefixGenerator.render(ots);
        assertThat(out).contains("enum Alpha");
        assertThat(out).contains("enum Zebra");
        assertThat(out.indexOf("enum Alpha")).isLessThan(out.indexOf("enum Zebra"));
    }

    @Test
    @DisplayName("render emits classes after enums in topological order")
    void renderClassesAfterEnums() {
        List<ResolvedObjectType> ots = List.of(
                ResolvedObjectType.enumOf("CustomerTier", List.of(new ResolvedObjectType.EnumValue("VIP", "VIP", 1))),
                ResolvedObjectType.classOf("customer", "Customer", List.of(
                        ResolvedObjectType.attr("tier", "object", "CustomerTier", null))),
                ResolvedObjectType.classOf("order", "Order", List.of()));
        String out = DomainTypePrefixGenerator.render(ots);
        int enumIdx = out.indexOf("enum CustomerTier");
        int customerIdx = out.indexOf("class Customer");
        int orderIdx = out.indexOf("class Order");
        assertThat(enumIdx).isLessThan(customerIdx);
        assertThat(customerIdx).isLessThan(orderIdx);
    }

    @Test
    @DisplayName("render maps primitive|object|list|map attribute dataTypes to Groovy field types")
    void renderMapsAttributeTypes() {
        List<ResolvedObjectType> ots = List.of(
                ResolvedObjectType.classOf("order", "Order", List.of(
                        ResolvedObjectType.attr("name",   "primitive", "string",  null),
                        ResolvedObjectType.attr("qty",    "primitive", "number",  null),
                        ResolvedObjectType.attr("active", "primitive", "boolean", null),
                        ResolvedObjectType.attr("date",   "primitive", "date",    null),
                        ResolvedObjectType.attr("cust",   "object",    "Customer",null),
                        ResolvedObjectType.attr("items",  "list",      "Item",    null),
                        ResolvedObjectType.attr("byKey",  "map",       "string",  "Item"))));
        String out = DomainTypePrefixGenerator.render(ots);
        assertThat(out).contains("String name");
        assertThat(out).contains("Integer qty");
        assertThat(out).contains("Boolean active");
        assertThat(out).contains("Date date");
        assertThat(out).contains("Customer cust");
        assertThat(out).contains("List<Item> items");
        assertThat(out).contains("Map<String, Item> byKey");
    }

    @Test
    @DisplayName("renderEnum produces 'enum Name { A, B }' with sorted entries")
    void renderEnum() {
        ResolvedObjectType ot = ResolvedObjectType.enumOf("Color", List.of(
                new ResolvedObjectType.EnumValue("BLUE",  "blue",  3),
                new ResolvedObjectType.EnumValue("RED",   "red",   1),
                new ResolvedObjectType.EnumValue("GREEN", "green", 2)));
        String out = DomainTypePrefixGenerator.renderEnum(ot);
        assertThat(out).isEqualTo("enum Color { RED, GREEN, BLUE }");
    }

    @Test
    @DisplayName("renderClass produces a Groovy class body")
    void renderClass() {
        ResolvedObjectType ot = ResolvedObjectType.classOf("x", "X", List.of(
                ResolvedObjectType.attr("a", "primitive", "string", null),
                ResolvedObjectType.attr("b", "primitive", "number", null)));
        String out = DomainTypePrefixGenerator.renderClass(ot);
        assertThat(out).isEqualTo("class X {\n    String a\n    Integer b\n}");
    }
}
