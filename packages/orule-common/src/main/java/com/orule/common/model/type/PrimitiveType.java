package com.orule.common.model.type;

import java.util.Set;

/**
 * 原子类型 Variant（RFC-0031 §3.1）。
 *
 * <p>支持的原子类型：string / number / boolean / date。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * { "kind": "primitive", "name": "string" }
 * }</pre>
 */
public record PrimitiveType(String name) implements Type {

    private static final Set<String> VALID_NAMES = Set.of("string", "number", "boolean", "date");

    public static final PrimitiveType STRING  = new PrimitiveType("string");
    public static final PrimitiveType NUMBER  = new PrimitiveType("number");
    public static final PrimitiveType BOOLEAN = new PrimitiveType("boolean");
    public static final PrimitiveType DATE    = new PrimitiveType("date");

    public PrimitiveType {
        if (name == null || !VALID_NAMES.contains(name)) {
            throw new IllegalArgumentException(
                "Unknown primitive type: " + name + " (valid: " + VALID_NAMES + ")");
        }
    }

    @Override
    public String kind() {
        return "primitive";
    }
}
