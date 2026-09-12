package com.orule.common.model.type;

/**
 * 列表类型 Variant（RFC-0031 §3.1）。
 *
 * <p>elementType 可以是任意 Type（含嵌套）。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * { "kind": "list", "elementType": { "kind": "primitive", "name": "string" } }
 * }</pre>
 */
public record ListType(Type elementType) implements Type {

    public ListType {
        if (elementType == null) {
            throw new IllegalArgumentException("elementType must not be null");
        }
    }

    @Override
    public String kind() {
        return "list";
    }
}
