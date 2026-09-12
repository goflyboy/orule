package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ObjectType 元数据实体（RFC-0032 §3.3）。
 *
 * <p>既承载 CLASS（普通对象）也承载 ENUM（枚举），通过 {@link Kind} 字段区分。
 * enum 类型的 ObjectType 通过 {@link #enumValues} 存储值列表，可被多个
 * AttributeType 通过 programCode 引用，实现 enum 跨 attribute 复用。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code programCode}：API/DSL 中可被引用的代码（如 "Customer" / "CustomerTier"）</li>
 *   <li>{@code kind}：CLASS（普通对象）/ ENUM（枚举）</li>
 *   <li>{@code enumValues}：仅 kind=ENUM 时使用，存 [{code, label, sortOrder}, ...]</li>
 *   <li>{@code attributes}：仅 kind=CLASS 时有内容（kind=ENUM 时为空列表）</li>
 * </ul>
 *
 * <p>技术债 TD-001：DomainMeta 直接使用本实体（保留 JPA 依赖），未来解耦。
 */
@Entity
@Table(name = "object_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_object_domain_program_code",
                       columnNames = {"domain_id", "program_code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ObjectType {

    /** ObjectType 种类：CLASS（普通对象）/ ENUM（枚举） */
    public enum Kind { CLASS, ENUM }

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", nullable = false)
    private DomainType domain;

    @Column(name = "program_code", nullable = false, length = 64)
    private String programCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Kind kind = Kind.CLASS;

    /** 仅 kind=ENUM 时使用，存 [{code, label, sortOrder}, ...] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_values", columnDefinition = "JSON")
    private List<EnumValue> enumValues;

    @OneToMany(mappedBy = "object", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AttributeType> attributes = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** enum 值定义（嵌入 ObjectType）。 */
    public record EnumValue(String code, String label, Integer sortOrder) {}
}
