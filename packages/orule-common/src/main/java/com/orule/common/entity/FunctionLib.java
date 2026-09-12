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
 * FunctionLib 实体（RFC-0032 §3.3 重命名）。
 *
 * <p>signature 字段保持 JSON（沿用 RFC-0031），存 {@code FunctionSignature}（参数 + 返回 Type 树）。
 * 字段名 code → programCode，列名同步调整。
 *
 * <p>技术债 TD-002：signature 仍为 JSON 列，与 attribute_type 3 列设计不一致；MVP 稳定后重构。
 */
@Entity
@Table(name = "function_lib", uniqueConstraints = {
    @UniqueConstraint(name = "uk_func_program_code", columnNames = "program_code")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FunctionLib {
    @Id
    private String id;

    @Column(name = "program_code", nullable = false, unique = true, length = 64)
    private String programCode;

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
