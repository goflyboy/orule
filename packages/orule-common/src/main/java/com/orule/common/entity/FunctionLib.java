package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

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

    @Column(nullable = false, length = 255)
    private String signature;

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
