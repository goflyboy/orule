package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * AttributeType 实体（RFC-0032 §3.3）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code dataType}：kind 标签，取值 primitive / object / list / map</li>
 *   <li>{@code subDataTypeProgramCode}：目标类型 code（primitive.name 或 object/list/map 的目标 programCode）</li>
 *   <li>{@code subDataTypeProgramCode2}：仅 map 使用（value 类型的 programCode 或 primitive name）</li>
 * </ul>
 *
 * <p>Type 工厂（{@code TypeFactory.buildType}）根据这 3 列构造完整的 {@code Type} 树。
 */
@Entity
@Table(name = "attribute_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_attr_object_program_code",
                       columnNames = {"object_id", "program_code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AttributeType {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_id", nullable = false)
    private ObjectType object;

    @Column(name = "program_code", nullable = false, length = 64)
    private String programCode;

    @Column(nullable = false, length = 128)
    private String name;

    /** Type 判别标签：primitive | object | list | map */
    @Column(name = "data_type", nullable = false, length = 32)
    private String dataType;

    /** primitive.name 或 object/list/map 的目标 programCode */
    @Column(name = "sub_data_type_program_code", length = 64)
    private String subDataTypeProgramCode;

    /** 仅 map 使用（value 类型的 programCode） */
    @Column(name = "sub_data_type_program_code2", length = 64)
    private String subDataTypeProgramCode2;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
