package com.orule.common.model.type;

/**
 * 对象引用 Variant（RFC-0032 §3.1）。
 *
 * <p>指向同一 DomainType 下的另一个 ObjectType.programCode（CLASS 或 ENUM）。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * { "kind": "object", "programCode": "Customer" }
 * }</pre>
 *
 * <p><b>RFC-0032 修订</b>：替代 RFC-0031 的 model.type.ObjectType；字段名
 * {@code objectCode} → {@code programCode}。
 *
 * <p><b>MVP 约束</b>：SimpleTS 表达式不允许继续访问 ObjectType 内部属性
 * （详见 RFC-0018 FieldValidator 修订）。
 */
public record ObjectRef(String programCode) implements Type {

    public ObjectRef {
        if (programCode == null || programCode.isBlank()) {
            throw new IllegalArgumentException("programCode must not be blank");
        }
    }

    @Override
    public String kind() {
        return "object";
    }
}
