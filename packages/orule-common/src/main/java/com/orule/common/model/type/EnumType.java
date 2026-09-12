package com.orule.common.model.type;

import java.util.List;
import java.util.Optional;

/**
 * 内联枚举 Variant（RFC-0031 §3.1）。
 *
 * <p>enum 定义随 Type 定义一起存储，无需独立的 enum_value 表。
 *
 * <p>JSON 形态：
 * <pre>{@code
 * {
 *   "kind": "enum",
 *   "enumCode": "CustomerTier",
 *   "values": [
 *     { "code": "VIP", "label": "VIP 客户", "sortOrder": 1 },
 *     { "code": "NORMAL", "label": "普通客户", "sortOrder": 2 }
 *   ]
 * }
 * }</pre>
 */
@Type.PolymorphicConfig
public record EnumType(
        String enumCode,
        List<EnumValue> values
) implements Type {

    /** 单个枚举值（code 必填；label/sortOrder 可选） */
    public record EnumValue(String code, String label, Integer sortOrder) {}

    public EnumType {
        if (enumCode == null || enumCode.isBlank()) {
            throw new IllegalArgumentException("enumCode must not be blank");
        }
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        values = List.copyOf(values); // 不可变
    }

    /** 按 code 查找枚举值，未找到返回 Optional.empty() */
    public Optional<EnumValue> findByCode(String code) {
        return values.stream().filter(v -> v.code().equals(code)).findFirst();
    }

    /** 校验 code 是否在 enum 中存在 */
    public boolean containsCode(String code) {
        return findByCode(code).isPresent();
    }

    @Override
    public String kind() {
        return "enum";
    }
}
