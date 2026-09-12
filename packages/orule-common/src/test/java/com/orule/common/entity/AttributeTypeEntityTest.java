package com.orule.common.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * entity.AttributeType 字段校验（RFC-0032 §3.3）。
 *
 * <p>RFC-0032 重构：
 * <ul>
 *   <li>{@code code} → {@code programCode}</li>
 *   <li>{@code type_json} 删除，改为 3 列：
 *     {@code dataType} + {@code subDataTypeProgramCode} + {@code subDataTypeProgramCode2}</li>
 * </ul>
 */
public class AttributeTypeEntityTest {

    @Test
    @DisplayName("AttributeType 持有 programCode 字段（非 code）")
    void programCode_field() {
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("id")
            .name("Customer ID")
            .dataType("primitive")
            .subDataTypeProgramCode("string")
            .isRequired(true)
            .build();

        assertEquals("id", attr.getProgramCode());
    }

    @Test
    @DisplayName("AttributeType dataType=primitive + sub=string 表示 primitive")
    void primitive_representation() {
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("name")
            .name("Customer Name")
            .dataType("primitive")
            .subDataTypeProgramCode("string")
            .build();

        assertEquals("primitive", attr.getDataType());
        assertEquals("string", attr.getSubDataTypeProgramCode());
        assertEquals(null, attr.getSubDataTypeProgramCode2());
    }

    @Test
    @DisplayName("AttributeType dataType=object + sub=programCode 表示 object 引用")
    void object_representation() {
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("tier")
            .name("Customer Tier")
            .dataType("object")
            .subDataTypeProgramCode("CustomerTier")
            .build();

        assertEquals("object", attr.getDataType());
        assertEquals("CustomerTier", attr.getSubDataTypeProgramCode());
    }

    @Test
    @DisplayName("AttributeType dataType=list + sub=elementType")
    void list_representation() {
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("itemPrices")
            .name("Item Prices")
            .dataType("list")
            .subDataTypeProgramCode("number")
            .build();

        assertEquals("list", attr.getDataType());
        assertEquals("number", attr.getSubDataTypeProgramCode());
    }

    @Test
    @DisplayName("AttributeType dataType=map + sub=key + sub2=value")
    void map_representation() {
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("taxBreakdown")
            .name("Tax Breakdown")
            .dataType("map")
            .subDataTypeProgramCode("string")
            .subDataTypeProgramCode2("number")
            .build();

        assertEquals("map", attr.getDataType());
        assertEquals("string", attr.getSubDataTypeProgramCode());
        assertEquals("number", attr.getSubDataTypeProgramCode2());
    }

    @Test
    @DisplayName("AttributeType 删除 type 字段（typeJson 已拆为 3 列）")
    void noTypeJsonField() {
        // 编译期检查：实体不再暴露 getType() / setType() 方法
        // （通过反射验证属性不存在）
        AttributeType attr = AttributeType.builder()
            .id("attr-1")
            .programCode("name")
            .name("Customer Name")
            .dataType("primitive")
            .subDataTypeProgramCode("string")
            .build();

        try {
            AttributeType.class.getDeclaredMethod("getType");
            org.junit.jupiter.api.Assertions.fail("getType() should not exist after RFC-0032");
        } catch (NoSuchMethodException e) {
            // expected
        }
    }
}
