package com.orule.common.model.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Type 系统 4 Variant 校验（RFC-0032 §3.1）。
 *
 * <p>RFC-0032 把 Type Variant 从 5 个（PrimitiveType/EnumType/ObjectType/ListType/MapType）
 * 减为 4 个：EnumType 删；model.type.ObjectType 重命名为 ObjectRef。
 */
public class TypeVariantsTest {

    @Test
    @DisplayName("PrimitiveType 接受合法 name 且 kind 返回 primitive")
    void primitiveType_validName() {
        PrimitiveType pt = new PrimitiveType("string");
        assertEquals("primitive", pt.kind());
        assertEquals("string", pt.name());
    }

    @Test
    @DisplayName("PrimitiveType 含有预定义常量 STRING/NUMBER/BOOLEAN/DATE")
    void primitiveType_constants() {
        assertEquals("string", PrimitiveType.STRING.name());
        assertEquals("number", PrimitiveType.NUMBER.name());
        assertEquals("boolean", PrimitiveType.BOOLEAN.name());
        assertEquals("date", PrimitiveType.DATE.name());
    }

    @Test
    @DisplayName("PrimitiveType 拒绝非法 name")
    void primitiveType_invalidName() {
        assertThrows(IllegalArgumentException.class,
            () -> new PrimitiveType("bigint"));
    }

    @Test
    @DisplayName("ObjectRef 接受非空 programCode 且 kind 返回 object")
    void objectRef_validProgramCode() {
        ObjectRef ref = new ObjectRef("Customer");
        assertEquals("object", ref.kind());
        assertEquals("Customer", ref.programCode());
    }

    @Test
    @DisplayName("ObjectRef 拒绝 blank programCode")
    void objectRef_blankProgramCode() {
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectRef(""));
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectRef("   "));
        assertThrows(IllegalArgumentException.class,
            () -> new ObjectRef(null));
    }

    @Test
    @DisplayName("ListType 嵌套 elementType 且 kind 返回 list")
    void listType_nested() {
        ListType list = new ListType(PrimitiveType.STRING);
        assertEquals("list", list.kind());
        assertEquals(PrimitiveType.STRING, list.elementType());
    }

    @Test
    @DisplayName("MapType 含 keyType + valueType 且 kind 返回 map")
    void mapType_keyValue() {
        MapType map = new MapType(PrimitiveType.STRING, PrimitiveType.NUMBER);
        assertEquals("map", map.kind());
        assertEquals(PrimitiveType.STRING, map.keyType());
        assertEquals(PrimitiveType.NUMBER, map.valueType());
    }

    @Test
    @DisplayName("Type sealed interface 仅 permits 4 个 variant（无 EnumType）")
    void sealedInterface_fourPermits() {
        // 编译期通过：PrimitiveType/ObjectRef/ListType/MapType 都能实现 Type
        Type t1 = PrimitiveType.STRING;
        Type t2 = new ObjectRef("Customer");
        Type t3 = new ListType(PrimitiveType.STRING);
        Type t4 = new MapType(PrimitiveType.STRING, PrimitiveType.NUMBER);
        assertTrue(t1 instanceof PrimitiveType);
        assertTrue(t2 instanceof ObjectRef);
        assertTrue(t3 instanceof ListType);
        assertTrue(t4 instanceof MapType);
    }
}
