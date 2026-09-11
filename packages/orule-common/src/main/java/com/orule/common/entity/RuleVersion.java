package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "rule_version")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleVersion {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "MAINTENANCE";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "simple_ts", columnDefinition = "TEXT")
    private String simpleTs;

    @Column(name = "groovy_source", columnDefinition = "TEXT")
    private String groovySource;

    @Column(columnDefinition = "TEXT")
    private String changelog;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;
}
