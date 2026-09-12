package com.orule.common.entity;

import com.orule.common.model.type.FunctionSignature;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * FunctionLib 实体（RFC-0031 §3.3）。
 *
 * <p>signature 字段为 JSON，存储函数签名（参数列表 + 返回类型，Type 树）。
 */
@Entity
@Table(name = "function_lib")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FunctionLib {
    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 函数签名（参数 + 返回类型的 Type 树，JSON） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    private FunctionSignature signature;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category;

    @Column(name = "is_builtin", nullable = false)
    @Builder.Default
    private Boolean isBuiltin = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
