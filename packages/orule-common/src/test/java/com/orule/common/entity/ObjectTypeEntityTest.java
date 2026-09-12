package com.orule.common.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * entity.ObjectType 字段校验（RFC-0032 §3.3）。
 *
 * <p>RFC-0032 重构：
 * <ul>
 *   <li>{@code code} → {@code programCode}</li>
 *   <li>新增 {@code kind}（CLASS | ENUM）</li>
 *   <li>新增 {@code enumValues}（仅 kind=ENUM，{@code List<EnumValue>}）</li>
 *   <li>{@code attributes} 关联保留</li>
 * </ul>
 */
public class ObjectTypeEntityTest {

    @Test
    @DisplayName("ObjectType.Kind 枚举含 CLASS 与 ENUM")
    void kind_enumValues() {
        ObjectType.Kind[] kinds = ObjectType.Kind.values();
        assertEquals(2, kinds.length);
        assertSame(ObjectType.Kind.CLASS, kinds[0]);
        assertSame(ObjectType.Kind.ENUM, kinds[1]);
    }

    @Test
    @DisplayName("ObjectType 持有 programCode 字段（非 code）")
    void programCode_field() {
        ObjectType obj = ObjectType.builder()
            .id("obj-1")
            .programCode("Customer")
            .name("Customer Entity")
            .kind(ObjectType.Kind.CLASS)
            .build();

        assertEquals("Customer", obj.getProgramCode());
    }

    @Test
    @DisplayName("ObjectType kind=CLASS 时 enumValues 为 null")
    void classKind_enumValuesNull() {
        ObjectType obj = ObjectType.builder()
            .id("obj-1")
            .programCode("Customer")
            .name("Customer")
            .kind(ObjectType.Kind.CLASS)
            .build();

        assertEquals(ObjectType.Kind.CLASS, obj.getKind());
        assertEquals(null, obj.getEnumValues());
    }

    @Test
    @DisplayName("ObjectType kind=ENUM 时 enumValues 可存放 List<EnumValue>")
    void enumKind_enumValuesSet() {
        ObjectType.EnumValue vip = new ObjectType.EnumValue("VIP", "VIP 客户", 1);
        ObjectType.EnumValue gold = new ObjectType.EnumValue("GOLD", "金卡客户", 2);

        ObjectType tier = ObjectType.builder()
            .id("obj-tier")
            .programCode("CustomerTier")
            .name("Customer Tier")
            .kind(ObjectType.Kind.ENUM)
            .enumValues(List.of(vip, gold))
            .build();

        assertEquals(ObjectType.Kind.ENUM, tier.getKind());
        assertNotNull(tier.getEnumValues());
        assertEquals(2, tier.getEnumValues().size());
        assertEquals("VIP", tier.getEnumValues().get(0).code());
        assertEquals("VIP 客户", tier.getEnumValues().get(0).label());
        assertEquals(1, tier.getEnumValues().get(0).sortOrder());
    }

    @Test
    @DisplayName("ObjectType.EnumValue 是 record（code/label/sortOrder）")
    void enumValue_record() {
        ObjectType.EnumValue v = new ObjectType.EnumValue("VIP", "VIP 客户", 1);
        assertEquals("VIP", v.code());
        assertEquals("VIP 客户", v.label());
        assertEquals(1, v.sortOrder());
    }

    @Test
    @DisplayName("ObjectType 默认 kind=CLASS（builder.Default）")
    void defaultKind_class() {
        ObjectType obj = ObjectType.builder()
            .id("obj-1")
            .programCode("Customer")
            .name("Customer")
            .build();

        assertNotNull(obj.getKind());
        assertEquals(ObjectType.Kind.CLASS, obj.getKind());
    }

    @Test
    @DisplayName("ObjectType.attributes 默认空列表")
    void attributes_defaultEmpty() {
        ObjectType obj = ObjectType.builder()
            .id("obj-1")
            .programCode("Customer")
            .name("Customer")
            .kind(ObjectType.Kind.CLASS)
            .build();

        assertNotNull(obj.getAttributes());
        assertTrue(obj.getAttributes().isEmpty());
    }
}
