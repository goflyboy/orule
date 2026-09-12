package com.orule.common.entity;

import com.orule.common.model.type.ObjectRef;
import com.orule.common.model.type.Type;
import com.orule.common.model.type.TypeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-0032 关键场景集成测试。
 *
 * <p>场景 A：CustomerTier 是 ObjectType(kind=ENUM)，被 customer.tier 和
 * order.customerTier 两个 attribute 复用（共享 enum 定义）。
 *
 * <p>场景 B：Order.attributes 包含 list/map/object 各种 Type，
 * TypeFactory 能正确组装为 Type 树。
 */
public class Rfc0032ScenariosTest {

    @Test
    @DisplayName("场景 A：CustomerTier(ENUM) 跨 attribute 共享")
    void enumCrossAttributeSharing() {
        // 1. 定义 CustomerTier ObjectType(kind=ENUM)，values 单一来源
        ObjectType.EnumValue vip = new ObjectType.EnumValue("VIP", "VIP 客户", 1);
        ObjectType.EnumValue gold = new ObjectType.EnumValue("GOLD", "金卡客户", 2);
        ObjectType customerTier = ObjectType.builder()
            .id("obj-tier")
            .programCode("CustomerTier")
            .name("Customer Tier")
            .kind(ObjectType.Kind.ENUM)
            .enumValues(List.of(vip, gold))
            .build();

        // 2. Customer.tier 字段引用 CustomerTier
        AttributeType customerTierAttr = AttributeType.builder()
            .id("attr-cust-tier")
            .programCode("tier")
            .name("Customer Tier")
            .dataType("object")
            .subDataTypeProgramCode("CustomerTier")
            .build();

        // 3. Order.customerTier 字段也引用同一个 CustomerTier
        AttributeType orderCustomerTierAttr = AttributeType.builder()
            .id("attr-order-cust-tier")
            .programCode("customerTier")
            .name("Customer Tier")
            .dataType("object")
            .subDataTypeProgramCode("CustomerTier")
            .build();

        Map<String, ObjectType> objectsByCode = Map.of("CustomerTier", customerTier);

        // 4. TypeFactory 组装：两个 attribute 都得到 ObjectRef("CustomerTier")
        Type t1 = TypeFactory.buildType(customerTierAttr, objectsByCode);
        Type t2 = TypeFactory.buildType(orderCustomerTierAttr, objectsByCode);
        assertEquals(new ObjectRef("CustomerTier"), t1);
        assertEquals(new ObjectRef("CustomerTier"), t2);
        assertEquals(t1, t2);

        // 5. enum 定义只存一份（不会因两个 attribute 引用而重复）
        assertNotNull(customerTier.getEnumValues());
        assertEquals(2, customerTier.getEnumValues().size());
    }

    @Test
    @DisplayName("场景 B：Order 含 list/map/object/primitive 各种 Type")
    void orderAttributes_fullTypes() {
        AttributeType orderId = AttributeType.builder()
            .id("a1").programCode("id").name("Order ID")
            .dataType("primitive").subDataTypeProgramCode("string").build();
        AttributeType orderTotal = AttributeType.builder()
            .id("a2").programCode("totalAmount").name("Total")
            .dataType("primitive").subDataTypeProgramCode("number").build();
        AttributeType orderCustomer = AttributeType.builder()
            .id("a3").programCode("customer").name("Customer")
            .dataType("object").subDataTypeProgramCode("Customer").build();
        AttributeType orderPrices = AttributeType.builder()
            .id("a4").programCode("itemPrices").name("Item Prices")
            .dataType("list").subDataTypeProgramCode("number").build();
        AttributeType orderTax = AttributeType.builder()
            .id("a5").programCode("taxBreakdown").name("Tax Breakdown")
            .dataType("map").subDataTypeProgramCode("string").subDataTypeProgramCode2("number").build();

        Map<String, ObjectType> objectsByCode = Map.of();

        Type tId = TypeFactory.buildType(orderId, objectsByCode);
        Type tTotal = TypeFactory.buildType(orderTotal, objectsByCode);
        Type tCust = TypeFactory.buildType(orderCustomer, objectsByCode);
        Type tPrices = TypeFactory.buildType(orderPrices, objectsByCode);
        Type tTax = TypeFactory.buildType(orderTax, objectsByCode);

        assertEquals("primitive", tId.kind());
        assertEquals("primitive", tTotal.kind());
        assertEquals("object", tCust.kind());
        assertEquals("list", tPrices.kind());
        assertEquals("map", tTax.kind());
        assertEquals(new ObjectRef("Customer"), tCust);

        // list<number>
        assertTrue(tPrices instanceof com.orule.common.model.type.ListType);
        assertEquals(com.orule.common.model.type.PrimitiveType.NUMBER,
                     ((com.orule.common.model.type.ListType) tPrices).elementType());

        // map<string, number>
        com.orule.common.model.type.MapType taxMap =
            (com.orule.common.model.type.MapType) tTax;
        assertEquals(com.orule.common.model.type.PrimitiveType.STRING, taxMap.keyType());
        assertEquals(com.orule.common.model.type.PrimitiveType.NUMBER, taxMap.valueType());
    }
}
