package com.orule.common.entity;

import com.orule.common.model.type.Type;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * AttributeType 实体（RFC-0031 §3.3）。
 *
 * <p>存储字段说明：
 * <ul>
 *   <li>{@code dataType}：kind 标签，取值 primitive / enum / object / list / map</li>
 *   <li>{@code type}：完整 Type 结构（Jackson 多态反序列化为 sealed interface）</li>
 * </ul>
 */
@Entity
@Table(name = "attribute_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_attr_object_code", columnNames = {"object_id", "code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AttributeType {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_id", nullable = false)
    private ObjectType object;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** Type 判别标签：primitive | enum | object | list | map */
    @Column(name = "data_type", nullable = false, length = 32)
    private String dataType;

    /** 完整 Type 结构（JSON 树；Jackson 多态序列化） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "type_json", nullable = false, columnDefinition = "JSON")
    private Type type;

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
