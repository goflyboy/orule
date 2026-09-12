package com.orule.common.model.type;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Type 系统的根接口（RFC-0032 §3.1）。
 *
 * <p>RFC-0032 修订：4 个 Variant（PrimitiveType / ObjectRef / ListType / MapType），
 * 相比 RFC-0031 删除 EnumType（合入 ObjectType.kind=ENUM）。
 *
 * <p>Jackson 多态序列化通过 {@code @JsonTypeInfo} 与 {@code @JsonSubTypes} 注解
 * 完成；反序列化时根据 {@code kind} 字段选择目标子类。
 *
 * <p>新增类型（如 Set、Tuple、Record）只需：1) 新增 record 实现此接口；2) 在下方
 * {@link JsonSubTypes} 列表添加映射。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PrimitiveType.class, name = "primitive"),
    @JsonSubTypes.Type(value = ObjectRef.class,     name = "object"),
    @JsonSubTypes.Type(value = ListType.class,      name = "list"),
    @JsonSubTypes.Type(value = MapType.class,       name = "map"),
})
public sealed interface Type permits
        PrimitiveType, ObjectRef, ListType, MapType {

    /** Type 的判别字段，用于 JSON 多态反序列化 */
    String kind();
}
