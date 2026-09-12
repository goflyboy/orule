package com.orule.common.model.type;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Type 系统的根接口（RFC-0031）。
 *
 * <p>5 个 Variant 用 sealed 子类型表达，Jackson 多态序列化（{@code kind} 字段）
 * 持久化到 {@code attribute_type.type_json} 列。
 *
 * <p>新增类型（如 Set、Tuple、Record）只需：1) 新增 record 实现此接口；2) 在
 * {@link PolymorphicConfig} 添加子类型映射。
 */
public sealed interface Type permits
        PrimitiveType, EnumType, ObjectType, ListType, MapType {

    /** Type 的判别字段，用于 JSON 多态反序列化 */
    String kind();

    /**
     * Jackson 多态配置（注解附着在每个 Variant record 上）。
     *
     * <p>使用 {@code kind} 字段作为判别标签，反序列化时根据该字段选择目标子类。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PrimitiveType.class, name = "primitive"),
        @JsonSubTypes.Type(value = EnumType.class,      name = "enum"),
        @JsonSubTypes.Type(value = ObjectType.class,    name = "object"),
        @JsonSubTypes.Type(value = ListType.class,      name = "list"),
        @JsonSubTypes.Type(value = MapType.class,       name = "map"),
    })
    @interface PolymorphicConfig {}
}
