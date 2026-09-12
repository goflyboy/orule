package com.orule.common.model.type;

import com.orule.common.entity.AttributeType;
import com.orule.common.entity.ObjectType;

import java.util.Map;

/**
 * Type 工厂（RFC-0032 §3.5）。
 *
 * <p>根据 {@link AttributeType} 的 3 列结构（{@code dataType} +
 * {@code subDataTypeProgramCode} + {@code subDataTypeProgramCode2}）组装成完整 {@link Type} 树。
 *
 * <p>矩阵：
 * <table border="1">
 *   <tr><th>dataType</th><th>sub</th><th>sub2</th><th>结果</th></tr>
 *   <tr><td>primitive</td><td>primitive name</td><td>-</td><td>PrimitiveType(name)</td></tr>
 *   <tr><td>object</td><td>programCode</td><td>-</td><td>ObjectRef(programCode)</td></tr>
 *   <tr><td>list</td><td>element type</td><td>-</td><td>ListType(buildType(...))</td></tr>
 *   <tr><td>map</td><td>key type</td><td>value type</td><td>MapType(buildType(...), buildType(...))</td></tr>
 * </table>
 *
 * <p>MVP 限制（TD-003）：map 的 key 必须是 primitive；map 的 value 可以是 primitive 或 object。
 */
public final class TypeFactory {

    private TypeFactory() {}

    /**
     * 根据 attribute 的 3 列结构组装 Type。
     *
     * @param attr        attribute 实体（提供 dataType + sub + sub2）
     * @param objectsByCode 同 domain 下所有 ObjectType（programCode → ObjectType），保留参数以便未来扩展（如循环引用检查）
     * @return 完整 Type 树
     * @throws IllegalStateException dataType 非法 / map 缺 sub2 / map key 非 primitive
     */
    public static Type buildType(AttributeType attr, Map<String, ObjectType> objectsByCode) {
        if (attr == null || attr.getDataType() == null) {
            throw new IllegalStateException("AttributeType.dataType is null");
        }
        return switch (attr.getDataType()) {
            case "primitive" -> new PrimitiveType(attr.getSubDataTypeProgramCode());
            case "object"    -> new ObjectRef(attr.getSubDataTypeProgramCode());
            case "list"      -> buildList(attr.getSubDataTypeProgramCode(), objectsByCode);
            case "map"       -> buildMap(
                                    attr.getSubDataTypeProgramCode(),
                                    attr.getSubDataTypeProgramCode2(),
                                    objectsByCode);
            default -> throw new IllegalStateException("Unknown data_type: " + attr.getDataType());
        };
    }

    /**
     * 构造 list 的 elementType。
     * <p>sub 可以是 primitive name（如 "number"）或 object 的 programCode。
     */
    private static Type buildList(String programCode, Map<String, ObjectType> map) {
        if (programCode == null || programCode.isBlank()) {
            throw new IllegalStateException("list element type code is blank");
        }
        if (isPrimitive(programCode)) return new ListType(new PrimitiveType(programCode));
        return new ListType(new ObjectRef(programCode));
    }

    /**
     * 构造 map 的 key + value Type。
     * <p>MVP 限制（TD-003）：key 必须是 primitive；value 可以是 primitive 或 object。
     */
    private static Type buildMap(String keyCode, String valueCode, Map<String, ObjectType> map) {
        if (keyCode == null || keyCode.isBlank()) {
            throw new IllegalStateException("map key type code is blank");
        }
        if (valueCode == null || valueCode.isBlank()) {
            throw new IllegalStateException("map value type code is blank");
        }
        if (!isPrimitive(keyCode)) {
            throw new IllegalStateException(
                "Map key must be primitive, got: " + keyCode + " (MVP limitation, see RFC-0032 TD-003)");
        }
        Type keyType = new PrimitiveType(keyCode);
        Type valueType = isPrimitive(valueCode)
            ? new PrimitiveType(valueCode)
            : new ObjectRef(valueCode);
        return new MapType(keyType, valueType);
    }

    /**
     * 是否为合法 primitive name（string / number / boolean / date）。
     */
    public static boolean isPrimitive(String name) {
        if (name == null) return false;
        return "string".equals(name)
            || "number".equals(name)
            || "boolean".equals(name)
            || "date".equals(name);
    }
}
