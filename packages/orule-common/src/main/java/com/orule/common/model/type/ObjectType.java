package com.orule.common.model.type;

/**
 * 对象引用 Variant（RFC-0031 §3.1）。
 *
 * <p>指向同一 DomainType 下的另一个 ObjectType.code。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * { "kind": "object", "objectCode": "Address" }
 * }</pre>
 *
 * <p><b>MVP 约束</b>：SimpleTS 表达式不允许继续访问 ObjectType 内部属性
 * （详见 RFC-0031 §3.5.2）。ObjectType 内部仍有完整 fields 结构，供运行时
 * 类型检查和算法使用。
 */
@Type.PolymorphicConfig
public record ObjectType(String objectCode) implements Type {

    public ObjectType {
        if (objectCode == null || objectCode.isBlank()) {
            throw new IllegalArgumentException("objectCode must not be blank");
        }
    }

    @Override
    public String kind() {
        return "object";
    }
}
