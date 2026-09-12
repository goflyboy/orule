package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * DomainType 实体（RFC-0032 §3.3 重命名）。
 *
 * <p>字段名 code → programCode，列名同步调整。
 */
@Entity
@Table(name = "domain_type", uniqueConstraints = {
    @UniqueConstraint(name = "uk_domain_program_code", columnNames = "program_code")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DomainType {
    @Id
    private String id;

    @Column(name = "program_code", nullable = false, unique = true, length = 64)
    private String programCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_code", length = 64)
    private String ownerCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
