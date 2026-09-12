package com.orule.common.model.type;

import com.orule.common.entity.AttributeType;
import com.orule.common.entity.ObjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TypeFactory 单元测试（RFC-0032 §3.5）。
 *
 * <p>TypeFactory 根据 attribute 的 3 列结构组装成完整 Type 树：
 * <ul>
 *   <li>dataType=primitive + sub=name → PrimitiveType(name)</li>
 *   <li>dataType=object + sub=programCode → ObjectRef(programCode)</li>
 *   <li>dataType=list + sub=programCode → ListType(buildType(...))</li>
 *   <li>dataType=map + sub=key + sub2=value → MapType(buildType(...), buildType(...))</li>
 * </ul>
 */
public class TypeFactoryTest {

    private static AttributeType attr(String dataType, String sub, String sub2) {
        return AttributeType.builder()
            .id("a")
            .programCode("x")
            .name("x")
            .dataType(dataType)
            .subDataTypeProgramCode(sub)
            .subDataTypeProgramCode2(sub2)
            .build();
    }

    @Test
    @DisplayName("primitive/string → PrimitiveType(\"string\")")
    void primitiveString() {
        Type t = TypeFactory.buildType(attr("primitive", "string", null), Map.of());
        assertEquals(PrimitiveType.STRING, t);
    }

    @Test
    @DisplayName("primitive/number → PrimitiveType(\"number\")")
    void primitiveNumber() {
        Type t = TypeFactory.buildType(attr("primitive", "number", null), Map.of());
        assertEquals(PrimitiveType.NUMBER, t);
    }

    @Test
    @DisplayName("object/programCode → ObjectRef(programCode)")
    void objectRef() {
        Type t = TypeFactory.buildType(attr("object", "CustomerTier", null), Map.of());
        assertEquals(new ObjectRef("CustomerTier"), t);
    }

    @Test
    @DisplayName("list/primitive → ListType(PrimitiveType(...))")
    void listPrimitive() {
        Type t = TypeFactory.buildType(attr("list", "number", null), Map.of());
        assertEquals(new ListType(PrimitiveType.NUMBER), t);
    }

    @Test
    @DisplayName("map/string→number → MapType(PrimitiveType(\"string\"), PrimitiveType(\"number\"))")
    void mapPrimitiveValue() {
        Type t = TypeFactory.buildType(attr("map", "string", "number"), Map.of());
        assertEquals(new MapType(PrimitiveType.STRING, PrimitiveType.NUMBER), t);
    }

    @Test
    @DisplayName("map/object→object → MapType(PrimitiveType(\"string\"), ObjectRef(...))")
    void mapObjectValue() {
        Type t = TypeFactory.buildType(attr("map", "string", "Customer"), Map.of());
        MapType map = (MapType) t;
        assertEquals(PrimitiveType.STRING, map.keyType());
        assertEquals(new ObjectRef("Customer"), map.valueType());
    }

    @Test
    @DisplayName("map 缺少 sub2 → 抛 IllegalStateException")
    void mapMissingSub2() {
        assertThrows(IllegalStateException.class,
            () -> TypeFactory.buildType(attr("map", "string", null), Map.of()));
    }

    @Test
    @DisplayName("map key 非 primitive → 抛 IllegalStateException（MVP 限制）")
    void mapKeyNotPrimitive() {
        assertThrows(IllegalStateException.class,
            () -> TypeFactory.buildType(attr("map", "Customer", "number"), Map.of()));
    }

    @Test
    @DisplayName("未知 dataType → 抛 IllegalStateException")
    void unknownDataType() {
        assertThrows(IllegalStateException.class,
            () -> TypeFactory.buildType(attr("unknown", null, null), Map.of()));
    }

    @Test
    @DisplayName("isPrimitive 工具方法正确识别 4 个 primitive name")
    void isPrimitive_knownNames() {
        assertEquals(true, TypeFactory.isPrimitive("string"));
        assertEquals(true, TypeFactory.isPrimitive("number"));
        assertEquals(true, TypeFactory.isPrimitive("boolean"));
        assertEquals(true, TypeFactory.isPrimitive("date"));
        assertEquals(false, TypeFactory.isPrimitive("Customer"));
        assertEquals(false, TypeFactory.isPrimitive(""));
        assertEquals(false, TypeFactory.isPrimitive(null));
    }
}
