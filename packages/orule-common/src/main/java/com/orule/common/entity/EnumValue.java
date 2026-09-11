package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "enum_value", uniqueConstraints = {
    @UniqueConstraint(name = "uk_enum_value_code", columnNames = {"enum_id", "code"})
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class EnumValue {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enum_id", nullable = false)
    private EnumType enumType;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
