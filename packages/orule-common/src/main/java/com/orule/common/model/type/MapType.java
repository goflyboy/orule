package com.orule.common.model.type;

/**
 * 字典类型 Variant（RFC-0031 §3.1）。
 *
 * <p>keyType 与 valueType 均为 Type。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * {
 *   "kind": "map",
 *   "keyType":   { "kind": "primitive", "name": "string" },
 *   "valueType": { "kind": "object", "objectCode": "Order" }
 * }
 * }</pre>
 */
public record MapType(Type keyType, Type valueType) implements Type {

    public MapType {
        if (keyType == null) {
            throw new IllegalArgumentException("keyType must not be null");
        }
        if (valueType == null) {
            throw new IllegalArgumentException("valueType must not be null");
        }
    }

    @Override
    public String kind() {
        return "map";
    }
}
