package com.orule.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "rule_set_artifact")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RuleSetArtifact {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_set_id", nullable = false)
    private RuleSet ruleSet;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "storage_type", nullable = false, length = 16)
    private String storageType;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "storage_url", length = 512)
    private String storageUrl;

    @Column(name = "file_size", nullable = false)
    @Builder.Default
    private Long fileSize = 0L;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "rule_count", nullable = false)
    @Builder.Default
    private Integer ruleCount = 0;

    @Column(name = "included_versions", columnDefinition = "TEXT")
    private String includedVersions;

    @Column(nullable = false)
    @Builder.Default
    private Integer concurrency = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
